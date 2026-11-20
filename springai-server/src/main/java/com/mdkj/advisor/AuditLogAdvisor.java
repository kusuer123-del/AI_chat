package com.mdkj.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 审计日志 Advisor：在 ChatClient 接入层统一拦截请求与响应，业务代码零侵入。
 *
 * <p>记录 traceId、模型、请求字符数、工具清单、耗时与 token 用量；可通过 ai.audit-enabled 整体关闭。
 * 流式场景下中间块的 ChatResponse 不带 usage，这里只在拿到 usage 的那一帧落一次日志，
 * 首字延迟由控制层记账（见 AiChatController#stream）。</p>
 */
public class AuditLogAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger("AI_AUDIT");

    public static final String TRACE_ID = "traceId";
    private static final String START_AT = "audit.startAt";
    private static final String LOGGED = "audit.logged";

    private final boolean enabled;
    private final String model;

    public AuditLogAdvisor(boolean enabled, String model) {
        this.enabled = enabled;
        this.model = model;
    }

    @Override
    public int getOrder() {
        // 最后执行：包住记忆与检索 Advisor，日志里记录的是最终下发给模型的请求
        return 0;
    }

    @Override
    public AdvisedRequest before(AdvisedRequest request) {
        if (!enabled) {
            return request;
        }
        Map<String, Object> context = new HashMap<>(request.adviseContext());
        context.putIfAbsent(TRACE_ID, UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        context.put(START_AT, System.currentTimeMillis());

        log.info("[audit] trace={} model={} userChars={} systemChars={} tools={}",
                context.get(TRACE_ID), model, length(request.userText()), length(request.systemText()),
                request.functionNames());
        return AdvisedRequest.from(request).adviseContext(context).build();
    }

    @Override
    public AdvisedResponse after(AdvisedResponse advisedResponse) {
        if (!enabled || advisedResponse.adviseContext().containsKey(LOGGED)) {
            return advisedResponse;
        }
        ChatResponse response = advisedResponse.response();
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        if (usage == null || usage.getCompletionTokens() == null) {
            // 流式响应的中间块没有 usage，交给最后一帧或控制层记账
            return advisedResponse;
        }

        Map<String, Object> context = new HashMap<>(advisedResponse.adviseContext());
        Object startedAt = context.get(START_AT);
        long cost = startedAt instanceof Long start ? System.currentTimeMillis() - start : -1L;
        context.put(LOGGED, Boolean.TRUE);

        log.info("[audit] trace={} model={} cost={}ms promptTokens={} completionTokens={} replyChars={}",
                context.get(TRACE_ID), model, cost, usage.getPromptTokens(), usage.getCompletionTokens(),
                length(text(response)));
        return AdvisedResponse.from(advisedResponse).adviseContext(context).build();
    }

    private static String text(ChatResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }
}