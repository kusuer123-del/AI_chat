package com.mdkj.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mdkj.advisor.AuditLogAdvisor;
import com.mdkj.config.AiProperties;
import com.mdkj.dto.AgentRequest;
import com.mdkj.rag.RagService;
import com.mdkj.rag.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 单 Agent 编排器：计划 → 执行/观察循环 → 自检 → 生成答案。
 *
 * <p>与"把工具挂到 ChatClient 上"的区别就在于这个循环由我们自己的代码驱动：
 * 每一步调用哪些工具、是否继续、什么时候停，都是显式判断，并且受
 * 最大步数 / 最大工具次数 / 总超时三重预算约束——预算耗尽时主动收敛并如实告知信息不全。</p>
 *
 * <p>两类模型调用走两个客户端：规划、决策、自检这类内部调用用不带会话记忆的
 * internalClient（否则会把中间过程写进用户会话历史）；只有最终答案走统一接入层的
 * userFacingClient，从而复用系统提示词、审计日志与会话记忆。</p>
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final ChatClient internalClient;
    private final ChatClient userFacingClient;
    private final AgentToolRegistry tools;
    private final RagService ragService;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final AgentTraceStore traceStore;

    public AgentOrchestrator(ChatModel chatModel,
                             ChatClient chatClient,
                             AuditLogAdvisor auditLogAdvisor,
                             AgentToolRegistry tools,
                             RagService ragService,
                             AiProperties properties,
                             ObjectMapper objectMapper,
                             AgentTraceStore traceStore) {
        this.internalClient = ChatClient.builder(chatModel)
                .defaultSystem("你是一个严谨的任务规划与执行引擎，只按要求的 JSON 格式输出，不闲聊。")
                .defaultAdvisors(auditLogAdvisor)
                .build();
        this.userFacingClient = chatClient;
        this.tools = tools;
        this.ragService = ragService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.traceStore = traceStore;
    }

    /** 跑完计划、循环与自检，返回待生成最终答案的上下文。 */
    public AgentOutcome prepare(AgentRequest request, AgentListener listener) {
        long started = System.currentTimeMillis();
        String question = request.message() == null ? "" : request.message().trim();
        if (question.isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        AgentTrace trace = new AgentTrace();
        trace.setTraceId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        trace.setConversationId(request.conversationId());
        trace.setQuestion(question);
        trace.setStartedAt(started);

        // 1. 取证：需要时才检索，命中片段既进 Prompt 也回传前端
        List<RetrievedChunk> sources;
        try {
            sources = Boolean.TRUE.equals(request.rag()) ? ragService.search(question) : List.of();
        } catch (Exception ex) {
            // 检索失败不该让整轮失败：降级为无检索，并把降级原因写进轨迹
            sources = List.of();
            emit(trace, listener, AgentStep.ERROR, "检索失败已降级",
                    rootMessage(ex), question, "", 0);
            log.warn("[agent] 检索失败，降级为无检索：{}", rootMessage(ex));
        }
        if (!sources.isEmpty()) {
            String titles = sources.stream().map(RetrievedChunk::title).distinct().collect(Collectors.joining("、"));
            emit(trace, listener, AgentStep.OBSERVE, "检索知识库",
                    "命中 " + sources.size() + " 个片段：" + titles,
                    question, RagService.formatContext(sources), 0);
        } else {
            emit(trace, listener, AgentStep.OBSERVE, "检索知识库",
                    Boolean.TRUE.equals(request.rag()) ? "无命中，按通用知识回答并提示转人工" : "本次未开启检索",
                    question, "", 0);
        }

        // 2. 计划：先拆子任务，避免"想到哪问到哪"
        long planStart = System.currentTimeMillis();
        List<AgentTask> tasks = plan(question);
        trace.setTasks(tasks);
        emit(trace, listener, AgentStep.PLAN, "拆解子任务",
                tasks.stream()
                        .map(task -> task.goal() + (blank(task.toolHint()) ? "" : "（" + task.toolHint() + "）"))
                        .collect(Collectors.joining("；")),
                question, tasks.toString(), System.currentTimeMillis() - planStart);
        listener.onPlan(tasks);

        // 3. 执行循环
        AiProperties.Agent config = properties.getAgent();
        int maxSteps = request.maxSteps() == null
                ? config.getMaxSteps()
                : Math.max(1, Math.min(request.maxSteps(), 12));
        List<String> observations = new ArrayList<>();
        String draft = null;
        String stopReason = null;
        int toolCalls = 0;

        for (int step = 1; step <= maxSteps; step++) {
            if (System.currentTimeMillis() - started > config.getDeadlineMs()) {
                stopReason = "超出时间预算 " + config.getDeadlineMs() + "ms";
                break;
            }
            if (toolCalls >= config.getMaxToolCalls()) {
                stopReason = "工具调用达到上限 " + config.getMaxToolCalls() + " 次";
                break;
            }

            AgentAction action = decide(question, tasks, observations, trace, listener);
            if (action == null) {
                // 输出无法解析：已记一条 error 步骤，进入下一轮重试
                continue;
            }

            if (AgentAction.TYPE_TOOL.equals(action.type())) {
                if (!tools.has(action.tool())) {
                    String hint = "工具 [" + action.tool() + "] 不存在，可用：" + toolNames();
                    observations.add(hint);
                    emit(trace, listener, AgentStep.ERROR, "工具不存在", hint,
                            String.valueOf(action.tool()), "", 0);
                    continue;
                }
                toolCalls++;
                long toolStart = System.currentTimeMillis();
                String result;
                try {
                    String arguments = objectMapper.writeValueAsString(
                            action.arguments() == null ? Map.of() : action.arguments());
                    result = tools.invoke(action.tool(), arguments);
                } catch (Exception ex) {
                    // 工具失败不终止循环：把错误当观察结果喂回模型，让它换参数或换策略
                    result = "工具执行失败：" + rootMessage(ex) + "，请调整参数或改用其他方式";
                }
                long toolCost = System.currentTimeMillis() - toolStart;
                emit(trace, listener, AgentStep.TOOL, "调用工具 " + action.tool(),
                        blank(action.rationale()) ? "" : action.rationale(),
                        String.valueOf(action.arguments()), truncate(result, 400), toolCost);
                observations.add("[" + action.tool() + "] " + truncate(result, 500));
                continue;
            }

            if (AgentAction.TYPE_ANSWER.equals(action.type()) && !blank(action.answer())) {
                draft = action.answer();
                emit(trace, listener, AgentStep.THINK, "形成草稿答案",
                        blank(action.rationale()) ? "" : action.rationale(),
                        question, truncate(draft, 300), 0);
                break;
            }

            if (AgentAction.TYPE_FINISH.equals(action.type())) {
                stopReason = "模型判定无需继续";
                break;
            }

            emit(trace, listener, AgentStep.ERROR, "无法识别的动作", String.valueOf(action.type()), "", "", 0);
        }

        trace.setToolCalls(toolCalls);
        if (stopReason != null) {
            emit(trace, listener, AgentStep.ERROR, "触发终止条件", stopReason, "", "", 0);
        }

        // 4. 自检：草稿是否被证据支撑，不通过就把问题写进最终提示词强制修正
        String critique = null;
        if (config.isCritic() && !blank(draft)) {
            long criticStart = System.currentTimeMillis();
            critique = critique(question, draft, observations, sources);
            long criticCost = System.currentTimeMillis() - criticStart;
            if (blank(critique)) {
                emit(trace, listener, AgentStep.CRITIC, "自检通过", "草稿有证据支撑",
                        truncate(draft, 300), "", criticCost);
            } else {
                emit(trace, listener, AgentStep.CRITIC, "自检未通过", critique,
                        truncate(draft, 300), critique, criticCost);
            }
        }

        trace.setCostMs(System.currentTimeMillis() - started);
        String finalPrompt = buildFinalPrompt(question, observations, sources, draft, critique, stopReason);
        return new AgentOutcome(trace, finalPrompt, sources);
    }

    /** 最终答案流式生成：走统一接入层（带系统提示词、审计、会话记忆）。 */
    public Flux<String> streamFinal(AgentOutcome outcome) {
        ChatClient.ChatClientRequestSpec spec = userFacingClient.prompt().user(outcome.finalPrompt());
        String conversationId = outcome.trace().getConversationId();
        if (!blank(conversationId)) {
            spec = spec.advisors(advisor -> advisor.param(
                    AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId));
        }
        return spec.stream().chatResponse()
                .doOnNext(response -> captureUsage(outcome.trace(), response))
                .mapNotNull(this::contentOf)
                .filter(text -> !text.isEmpty());
    }

    /** 非流式执行：评测与 /agent/run 用。 */
    public AgentResult run(AgentRequest request) {
        AgentOutcome outcome = prepare(request, AgentListener.NOOP);
        try {
            ChatClient.ChatClientRequestSpec spec = userFacingClient.prompt().user(outcome.finalPrompt());
            String conversationId = outcome.trace().getConversationId();
            if (!blank(conversationId)) {
                spec = spec.advisors(advisor -> advisor.param(
                        AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId));
            }
            ChatResponse response = spec.call().chatResponse();
            captureUsage(outcome.trace(), response);
            AgentTrace trace = complete(outcome, contentOf(response), true, "done");
            return new AgentResult(trace, outcome.sources());
        } catch (RuntimeException ex) {
            // 失败也要落一份轨迹：排障时最想看的恰恰是失败那一轮
            outcome.trace().setError(rootMessage(ex));
            complete(outcome, "", false, "failed");
            throw ex;
        }
    }

    /** 收尾：写入答案、状态、总耗时并落盘。 */
    public AgentTrace complete(AgentOutcome outcome, String answer, boolean success, String status) {
        AgentTrace trace = outcome.trace();
        trace.setAnswer(answer);
        trace.setSuccess(success);
        trace.setStatus(status);
        trace.setCostMs(System.currentTimeMillis() - trace.getStartedAt());
        traceStore.save(trace);
        log.info("[agent] trace={} status={} steps={} toolCalls={} cost={}ms",
                trace.getTraceId(), status, trace.getSteps().size(), trace.getToolCalls(), trace.getCostMs());
        return trace;
    }

    // ------------------------------------------------------------------ 内部：三个"节点"

    private List<AgentTask> plan(String question) {
        String prompt = """
                你是任务规划器。把用户问题拆成 1-3 个可执行子任务，并标注可能需要用到的工具。

                可用工具：
                %s

                只输出 JSON，不要解释、不要 markdown 代码块：
                {"tasks":[{"goal":"子任务描述","toolHint":"工具名或 none"}]}

                用户问题：%s
                """.formatted(toolCatalog(), question);
        try {
            Optional<JsonNode> parsed = JsonUtils.parseObject(internalClient.prompt().user(prompt).call()
                    .chatResponse().getResult().getOutput().getText());
            List<AgentTask> tasks = new ArrayList<>();
            parsed.ifPresent(node -> node.path("tasks").forEach(item -> {
                String goal = item.path("goal").asText("").trim();
                String hint = item.path("toolHint").asText("none").trim();
                if (!goal.isEmpty()) {
                    tasks.add(new AgentTask(goal, "none".equalsIgnoreCase(hint) || hint.isEmpty() ? null : hint));
                }
            }));
            if (!tasks.isEmpty()) {
                return tasks.size() > 3 ? tasks.subList(0, 3) : tasks;
            }
        } catch (Exception ex) {
            log.warn("[agent] 规划失败，退化为单任务：{}", rootMessage(ex));
        }
        return List.of(new AgentTask(question, null));
    }

    private AgentAction decide(String question, List<AgentTask> tasks, List<String> observations,
                               AgentTrace trace, AgentListener listener) {
        String prompt = """
                你是任务执行器。根据用户问题、子任务和已有观察，决定下一步动作，只输出一个 JSON 对象。

                可用工具：
                %s

                子任务：
                %s

                已有观察：
                %s

                规则：
                1. 需要外部实时信息（天气、物流）且尚未查过时，输出：
                   {"action":"tool","tool":"工具名","arguments":{"参数":"值"},"rationale":"为什么调"}
                2. 信息已足够时，输出：
                   {"action":"answer","answer":"给用户的回答","rationale":"依据"}
                3. 同一工具同一参数不要重复调用；工具返回失败时调整参数或换方式，不要原样重试。
                4. 只能使用上面列出的工具名；不要编造工具返回里没有的数据。

                用户问题：%s

                只输出 JSON：
                """.formatted(toolCatalog(), taskCatalog(tasks), observationCatalog(observations), question);

        String raw;
        try {
            raw = internalClient.prompt().user(prompt).call().chatResponse().getResult().getOutput().getText();
        } catch (Exception ex) {
            emit(trace, listener, AgentStep.ERROR, "决策调用失败", rootMessage(ex), "", "", 0);
            return null;
        }

        Optional<JsonNode> parsed = JsonUtils.parseObject(raw);
        if (parsed.isEmpty()) {
            emit(trace, listener, AgentStep.ERROR, "决策输出无法解析", truncate(raw, 200), prompt, raw, 0);
            return null;
        }

        JsonNode node = parsed.get();
        String type = node.path("action").asText("").trim().toLowerCase();
        String tool = node.path("tool").asText("").trim();
        String answer = node.path("answer").asText("");
        String rationale = node.path("rationale").asText("");
        Map<String, Object> arguments = null;
        JsonNode argumentNode = node.path("arguments");
        if (argumentNode.isObject()) {
            arguments = objectMapper.convertValue(argumentNode, new TypeReference<Map<String, Object>>() {
            });
        }
        if (type.isEmpty()) {
            type = AgentAction.TYPE_ANSWER;
        }
        return new AgentAction(type, null, tool, arguments, answer, rationale);
    }

    /** 返回自检意见；null 表示通过。 */
    private String critique(String question, String draft, List<String> observations, List<RetrievedChunk> sources) {
        String prompt = """
                你是回答校验器。检查"待校验回答"是否存在这些问题：
                编造证据里没有的数字/政策/时效；把没查到的东西说成查到了；证据不足却硬答而没有提示转人工。

                只输出 JSON：
                {"grounded":true,"issues":""}
                或
                {"grounded":false,"issues":"一句话说明问题"}

                证据：
                %s

                用户问题：%s

                待校验回答：%s
                """.formatted(evidenceBlock(observations, sources), question, truncate(draft, 800));
        try {
            String raw = internalClient.prompt().user(prompt).call().chatResponse().getResult().getOutput().getText();
            Optional<JsonNode> parsed = JsonUtils.parseObject(raw);
            if (parsed.isEmpty()) {
                return null;
            }
            boolean grounded = parsed.get().path("grounded").asBoolean(true);
            String issues = parsed.get().path("issues").asText("").trim();
            return grounded || issues.isEmpty() ? null : issues;
        } catch (Exception ex) {
            log.warn("[agent] 自检失败，跳过：{}", rootMessage(ex));
            return null;
        }
    }

    // ------------------------------------------------------------------ 内部：提示词拼装

    private String buildFinalPrompt(String question, List<String> observations, List<RetrievedChunk> sources,
                                    String draft, String critique, String stopReason) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一名严谨的企业在线客服。请基于下面的证据回答用户问题，不要编造证据里没有的信息；")
                .append("证据不足时明确说明需要转人工确认，并告诉用户下一步可以怎么做。\n\n");

        if (!sources.isEmpty()) {
            prompt.append("【知识库资料】\n").append(RagService.formatContext(sources))
                    .append("\n引用知识库内容时在句末标注 [编号]。\n\n");
        }
        if (!observations.isEmpty()) {
            prompt.append("【工具执行结果】\n");
            observations.forEach(item -> prompt.append("- ").append(truncate(item, 500)).append('\n'));
            prompt.append('\n');
        }
        if (!blank(draft)) {
            prompt.append("【内部草稿（可参考，不要照抄）】\n").append(truncate(draft, 800)).append("\n\n");
        }
        if (!blank(critique)) {
            prompt.append("【自检发现的问题，必须修正】\n").append(critique).append("\n\n");
        }
        if (!blank(stopReason)) {
            prompt.append("【注意】本轮执行提前结束：").append(stopReason)
                    .append("。若因此信息不全，请如实说明，不要硬答。\n\n");
        }
        prompt.append("用户问题：").append(question).append("\n\n要求：简洁口语化，不超过 5 句话。");
        return prompt.toString();
    }

    private String toolCatalog() {
        List<ToolSpec> specs = tools.specs();
        if (specs.isEmpty()) {
            return "（当前没有可用工具）";
        }
        return specs.stream()
                .map(spec -> "- " + spec.name() + "：" + spec.description()
                        + "  参数：" + truncate(spec.schema(), 320))
                .collect(Collectors.joining("\n"));
    }

    private String toolNames() {
        return tools.specs().stream().map(ToolSpec::name).collect(Collectors.joining("、"));
    }

    private String taskCatalog(List<AgentTask> tasks) {
        if (tasks.isEmpty()) {
            return "（无）";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < tasks.size(); i++) {
            AgentTask task = tasks.get(i);
            text.append(i + 1).append(". ").append(task.goal());
            if (!blank(task.toolHint())) {
                text.append("（预期工具：").append(task.toolHint()).append("）");
            }
            text.append('\n');
        }
        return text.toString();
    }

    private String observationCatalog(List<String> observations) {
        if (observations.isEmpty()) {
            return "（暂无，可先调用工具获取）";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < observations.size(); i++) {
            text.append(i + 1).append(". ").append(observations.get(i)).append('\n');
        }
        return text.toString();
    }

    private String evidenceBlock(List<String> observations, List<RetrievedChunk> sources) {
        StringBuilder text = new StringBuilder();
        if (!sources.isEmpty()) {
            text.append(RagService.formatContext(sources)).append('\n');
        }
        observations.forEach(item -> text.append("- ").append(item).append('\n'));
        return text.length() == 0 ? "（本轮没有任何证据）" : text.toString();
    }

    // ------------------------------------------------------------------ 内部：杂项

    private void emit(AgentTrace trace, AgentListener listener, String type, String title,
                      String detail, String input, String output, long costMs) {
        AgentStep step = new AgentStep(trace.getSteps().size() + 1, type, title, detail,
                truncate(input, 500), truncate(output, 600), costMs);
        trace.getSteps().add(step);
        if (listener != null) {
            listener.onStep(step);
        }
    }

    private void captureUsage(AgentTrace trace, ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        if (usage.getPromptTokens() != null) {
            trace.setPromptTokens(usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() != null) {
            trace.setCompletionTokens(usage.getCompletionTokens());
        }
    }

    private String contentOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
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