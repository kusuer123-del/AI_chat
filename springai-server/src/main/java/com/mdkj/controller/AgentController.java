package com.mdkj.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mdkj.agent.AgentListener;
import com.mdkj.agent.AgentOrchestrator;
import com.mdkj.agent.AgentOutcome;
import com.mdkj.agent.AgentResult;
import com.mdkj.agent.AgentStep;
import com.mdkj.agent.AgentTask;
import com.mdkj.agent.AgentToolRegistry;
import com.mdkj.agent.AgentTrace;
import com.mdkj.agent.AgentTraceStore;
import com.mdkj.agent.EvalCase;
import com.mdkj.agent.EvalReport;
import com.mdkj.agent.EvalRunner;
import com.mdkj.agent.ToolSpec;
import com.mdkj.dto.AgentReply;
import com.mdkj.dto.AgentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 编排接口：执行、步骤流、轨迹回放、评测。
 *
 * <p>流式接口的事件顺序：plan（子任务）→ step（每次执行/观察）→ sources（召回片段）
 * → delta（最终答案逐块）→ done（轨迹摘要）。前端据此可以把"思考过程"边跑边显示。</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentOrchestrator orchestrator;
    private final AgentToolRegistry toolRegistry;
    private final AgentTraceStore traceStore;
    private final EvalRunner evalRunner;
    private final ObjectMapper objectMapper;

    public AgentController(AgentOrchestrator orchestrator,
                           AgentToolRegistry toolRegistry,
                           AgentTraceStore traceStore,
                           EvalRunner evalRunner,
                           ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.toolRegistry = toolRegistry;
        this.traceStore = traceStore;
        this.evalRunner = evalRunner;
        this.objectMapper = objectMapper;
    }

    /** 当前注册给 Agent 的工具清单（含 JSON Schema，可直接给前端做工具说明）。 */
    @GetMapping("/tools")
    public List<ToolSpec> tools() {
        return toolRegistry.specs();
    }

    /** 非流式执行：一次拿全轨迹，方便 curl 调试。 */
    @PostMapping("/run")
    public AgentReply run(@RequestBody AgentRequest request) {
        AgentResult result = orchestrator.run(request);
        AgentTrace trace = result.trace();
        return new AgentReply(trace.getTraceId(), trace.getAnswer(), trace.getTasks(), trace.getSteps(),
                result.sources(), trace.isSuccess(), trace.getStatus(), trace.getCostMs(),
                trace.getToolCalls(), trace.getPromptTokens(), trace.getCompletionTokens());
    }

    /** 流式执行：计划与每一步实时推送。 */
    @PostMapping(value = "/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> runStream(@RequestBody AgentRequest request) {
        return Flux.create(sink -> {
            Thread worker = new Thread(() -> {
                StringBuilder answer = new StringBuilder();
                AgentOutcome outcome = null;
                try {
                    outcome = orchestrator.prepare(request, new AgentListener() {
                        @Override
                        public void onPlan(List<AgentTask> tasks) {
                            emit(sink, "plan", Map.of("tasks", tasks));
                        }

                        @Override
                        public void onStep(AgentStep step) {
                            emit(sink, "step", step);
                        }
                    });
                    emit(sink, "sources", Map.of("sources", outcome.sources()));

                    orchestrator.streamFinal(outcome)
                            .doOnNext(chunk -> {
                                answer.append(chunk);
                                emit(sink, "delta", Map.of("text", chunk));
                            })
                            .blockLast();

                    AgentTrace trace = orchestrator.complete(outcome, answer.toString(), true, "done");
                    emit(sink, "done", summary(trace));
                } catch (Exception ex) {
                    log.warn("[agent] 流式执行失败: {}", rootMessage(ex));
                    if (outcome != null) {
                        outcome.trace().setError(rootMessage(ex));
                        orchestrator.complete(outcome, answer.toString(), false, "failed");
                    }
                    emit(sink, "error", Map.of("message", "Agent 执行中断：" + rootMessage(ex)));
                } finally {
                    sink.complete();
                }
            }, "agent-worker");
            worker.setDaemon(true);
            worker.start();
        });
    }

    /** 最近若干条轨迹摘要。 */
    @GetMapping("/traces")
    public List<Map<String, Object>> traces(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return traceStore.recent(limit);
    }

    /** 单条轨迹回放：每一步的输入输出都在里面。 */
    @GetMapping("/traces/{traceId}")
    public ResponseEntity<Object> trace(@PathVariable String traceId) {
        return traceStore.find(traceId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 评测用例清单。 */
    @GetMapping("/eval/cases")
    public List<EvalCase> evalCases() {
        return evalRunner.cases();
    }

    /** 跑评测：默认 5 条，跑完给出通过率、平均步数与失败原因。 */
    @PostMapping("/eval")
    public EvalReport eval(@RequestParam(value = "limit", defaultValue = "5") int limit) {
        return evalRunner.run(limit);
    }

    private Map<String, Object> summary(AgentTrace trace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", trace.getTraceId());
        payload.put("status", trace.getStatus());
        payload.put("success", trace.isSuccess());
        payload.put("costMs", trace.getCostMs());
        payload.put("toolCalls", trace.getToolCalls());
        payload.put("steps", trace.getSteps());
        payload.put("promptTokens", trace.getPromptTokens());
        payload.put("completionTokens", trace.getCompletionTokens());
        return payload;
    }

    private void emit(FluxSink<ServerSentEvent<String>> sink, String event, Object payload) {
        try {
            sink.next(ServerSentEvent.<String>builder(objectMapper.writeValueAsString(payload))
                    .event(event)
                    .build());
        } catch (Exception ex) {
            log.warn("[agent] SSE 事件序列化失败 event={}: {}", event, ex.getMessage());
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}