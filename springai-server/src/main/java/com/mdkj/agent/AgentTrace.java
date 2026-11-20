package com.mdkj.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 Agent 执行的完整轨迹：问题、计划、每一步、最终答案、预算用量。
 *
 * <p>这是"可观测"的载体：既能实时推给前端，也能落盘回放，还是评测的原始数据。</p>
 */
public class AgentTrace {

    private String traceId;
    private String conversationId;
    private String question;
    private List<AgentTask> tasks = new ArrayList<>();
    private List<AgentStep> steps = new ArrayList<>();
    private String answer;
    private String status = "running";
    private boolean success;
    private long startedAt;
    private long costMs;
    private int toolCalls;
    private Integer promptTokens;
    private Integer completionTokens;
    private String error;

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<AgentTask> getTasks() {
        return tasks;
    }

    public void setTasks(List<AgentTask> tasks) {
        this.tasks = tasks;
    }

    public List<AgentStep> getSteps() {
        return steps;
    }

    public void setSteps(List<AgentStep> steps) {
        this.steps = steps;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getCostMs() {
        return costMs;
    }

    public void setCostMs(long costMs) {
        this.costMs = costMs;
    }

    public int getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(int toolCalls) {
        this.toolCalls = toolCalls;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}