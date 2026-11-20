package com.mdkj.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mdkj.dto.ChatReply;
import com.mdkj.dto.ChatRequest;
import com.mdkj.memory.StoreChatMemory;
import com.mdkj.rag.RagService;
import com.mdkj.rag.RetrievedChunk;
import com.mdkj.tool.ExpressTools;
import com.mdkj.tool.WeatherTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话入口：一次性问答、SSE 流式问答、会话历史读取与清理。
 *
 * <p>RAG 在这里显式编排（先检索、把命中片段拼进 user 消息、命中结果随回答回传），
 * 而不是塞进 Advisor：Advisor 的 adviseContext 不会跨 call/stream 暴露给控制层，
 * 拿不到命中片段就没法做"回答可溯源"。改用显式编排后，两条链路共用同一份 sources。</p>
 */
@RestController
@RequestMapping("/api/v1/ai")
@CrossOrigin
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);

    private final ChatClient chatClient;
    private final StoreChatMemory chatMemory;
    private final RagService ragService;
    private final WeatherTools weatherTools;
    private final ExpressTools expressTools;
    private final ObjectMapper objectMapper;

    public AiChatController(ChatClient chatClient,
                            StoreChatMemory chatMemory,
                            RagService ragService,
                            WeatherTools weatherTools,
                            ExpressTools expressTools,
                            ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.ragService = ragService;
        this.weatherTools = weatherTools;
        this.expressTools = expressTools;
        this.objectMapper = objectMapper;
    }

    /** 一次性问答（非流式）。 */
    @PostMapping("/chat")
    public ChatReply chat(@RequestBody ChatRequest request) {
        String conversationId = conversationId(request);
        String question = requireText(request.message());
        List<RetrievedChunk> sources = retrieve(question, useRag(request));

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(composeUserText(question, sources))
                .advisors(advisor -> advisor.param(
                        AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId));
        if (useTools(request)) {
            spec = spec.tools(weatherTools, expressTools);
        }

        long start = System.currentTimeMillis();
        ChatResponse response = spec.call().chatResponse();
        long latency = System.currentTimeMillis() - start;

        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        String content = contentOf(response);
        log.info("[chat] trace={} conversation={} rag={} tools={} latency={}ms promptTokens={} completionTokens={}",
                traceOf(request), conversationId, useRag(request), useTools(request), latency,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());

        return new ChatReply(conversationId, content, sources,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                latency);
    }

    /** 流式问答：SSE，事件为 sources / delta / done / error。 */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest request) {
        String conversationId = conversationId(request);
        String question = requireText(request.message());
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        List<RetrievedChunk> sources = retrieve(question, useRag(request));

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(composeUserText(question, sources))
                .advisors(advisor -> advisor.param(
                        AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId));
        if (useTools(request)) {
            spec = spec.tools(weatherTools, expressTools);
        }

        long start = System.currentTimeMillis();
        AtomicLong firstTokenAt = new AtomicLong();
        AtomicInteger chunks = new AtomicInteger();
        AtomicReference<Integer> promptTokens = new AtomicReference<>();
        AtomicReference<Integer> completionTokens = new AtomicReference<>();

        Flux<ServerSentEvent<String>> deltas = spec.stream().chatResponse()
                .doOnNext(response -> captureUsage(response, promptTokens, completionTokens))
                .mapNotNull(this::contentOf)
                .filter(text -> !text.isEmpty())
                .map(text -> {
                    firstTokenAt.compareAndSet(0L, System.currentTimeMillis());
                    chunks.incrementAndGet();
                    return sse("delta", Map.of("text", text));
                });

        Flux<ServerSentEvent<String>> done = Flux.defer(() -> {
            long total = System.currentTimeMillis() - start;
            long firstToken = firstTokenAt.get() == 0L ? total : firstTokenAt.get() - start;
            log.info("[chat] stream done trace={} conversation={} rag={} tools={} firstToken={}ms total={}ms chunks={} promptTokens={} completionTokens={}",
                    traceId, conversationId, useRag(request), useTools(request), firstToken, total,
                    chunks.get(), promptTokens.get(), completionTokens.get());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", conversationId);
            payload.put("firstTokenMs", firstToken);
            payload.put("latencyMs", total);
            payload.put("chunks", chunks.get());
            payload.put("promptTokens", promptTokens.get());
            payload.put("completionTokens", completionTokens.get());
            return Flux.just(sse("done", payload));
        });

        Flux<ServerSentEvent<String>> body = deltas.concatWith(done).onErrorResume(ex -> {
            Throwable root = rootCause(ex);
            log.warn("[chat] stream failed trace={} conversation={}: {}", traceId, conversationId, root.getMessage());
            return Flux.just(sse("error", Map.of("message", "流式生成中断：" + root.getMessage())));
        });

        if (sources.isEmpty()) {
            return body;
        }
        ServerSentEvent<String> head = sse("sources", Map.of("sources", sources));
        return Flux.concat(Flux.just(head), body);
    }

    /** 会话历史（前端刷新页面后恢复对话，也用于验证"多实例共享会话"）。 */
    @GetMapping("/conversations/{conversationId}/messages")
    public List<Map<String, String>> history(@PathVariable String conversationId) {
        return chatMemory.history(conversationId).stream()
                .map(turn -> Map.of("role", turn.role(), "content", turn.text()))
                .toList();
    }

    /** 清空某个会话的上下文。 */
    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Object> clear(@PathVariable String conversationId) {
        long before = chatMemory.history(conversationId).size();
        chatMemory.clear(conversationId);
        log.info("[chat] 清空会话 {}，删除 {} 条历史", conversationId, before);
        return Map.of("conversationId", conversationId, "removed", before);
    }

    private String contentOf(ChatResponse response) {
        if (response == null) {
            return "";
        }
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            return "";
        }
        String text = generation.getOutput().getText();
        return text == null ? "" : text;
    }

    private void captureUsage(ChatResponse response, AtomicReference<Integer> promptTokens,
                              AtomicReference<Integer> completionTokens) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        if (usage.getPromptTokens() != null) {
            promptTokens.set(usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() != null) {
            completionTokens.set(usage.getCompletionTokens());
        }
    }

    private ServerSentEvent<String> sse(String event, Object payload) {
        try {
            return ServerSentEvent.<String>builder(objectMapper.writeValueAsString(payload))
                    .event(event)
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SSE 事件序列化失败", e);
        }
    }

    /** RAG 开启时把命中片段拼进 user 消息，并明确要求"资料无关时忽略"。 */
    private String composeUserText(String question, List<RetrievedChunk> sources) {
        if (sources.isEmpty()) {
            return question;
        }
        return """
                以下是从企业知识库检索到的资料，请优先依据资料回答，并在引用处标注 [编号]；
                如果资料与问题无关，直接说明并给出通用建议，不要编造。

                %s

                用户问题：%s
                """.formatted(RagService.formatContext(sources), question);
    }

    /** 检索失败只降级为无检索，不让整轮对话 500 —— 体验上"答得保守"好过"直接报错"。 */
    private List<RetrievedChunk> retrieve(String question, boolean enabled) {
        if (!enabled) {
            return List.of();
        }
        try {
            return ragService.search(question);
        } catch (RuntimeException ex) {
            log.warn("[chat] 检索失败，降级为无检索：{}", ex.getMessage());
            return List.of();
        }
    }

    private boolean useRag(ChatRequest request) {
        return Boolean.TRUE.equals(request.rag());
    }

    private boolean useTools(ChatRequest request) {
        return Boolean.TRUE.equals(request.tools());
    }

    private String conversationId(ChatRequest request) {
        String id = request.conversationId();
        return id == null || id.isBlank() ? UUID.randomUUID().toString().replace("-", "").substring(0, 16) : id;
    }

    private String requireText(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        return message.trim();
    }

    private String traceOf(ChatRequest request) {
        return request.conversationId() == null ? "-" : request.conversationId();
    }

    /** 把 Reactor 包装出的 "Stream processing failed" 剥掉，取真正的原因（如 401 InvalidApiKey）。 */
    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}