package com.mdkj.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 业务侧 AI 配置（与 spring.ai.dashscope.* 的模型配置分开：这里是"怎么用"，那边是"接哪家"）。
 */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 全局系统提示词，统一在 ChatClient 接入层注入。 */
    private String systemPrompt = "你是一个专业、简洁、有礼貌的企业智能客服助手。";

    /** 审计日志开关（自定义 Advisor 可整体开关，业务代码零侵入）。 */
    private boolean auditEnabled = true;

    private final Memory memory = new Memory();
    private final Rag rag = new Rag();
    private final Image image = new Image();
    private final Agent agent = new Agent();

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public void setAuditEnabled(boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }

    public Memory getMemory() {
        return memory;
    }

    public Rag getRag() {
        return rag;
    }

    public Image getImage() {
        return image;
    }

    public Agent getAgent() {
        return agent;
    }

    /** 会话记忆：store=memory|file|redis，redis 不可用时自动降级为 file。 */
    public static class Memory {

        private String store = "memory";
        private int window = 20;
        private String fileDir = "./data/chat-memory";
        private final Redis redis = new Redis();

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store;
        }

        public int getWindow() {
            return window;
        }

        public void setWindow(int window) {
            this.window = window;
        }

        public String getFileDir() {
            return fileDir;
        }

        public void setFileDir(String fileDir) {
            this.fileDir = fileDir;
        }

        public Redis getRedis() {
            return redis;
        }
    }

    public static class Redis {

        private String host = "127.0.0.1";
        private int port = 6379;
        private String password = "";
        private int database = 0;
        private String keyPrefix = "aics:chat:";
        private Duration ttl = Duration.ofHours(2);
        private int timeoutMs = 2000;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /** RAG：切片入库、检索参数、向量库落盘位置。 */
    public static class Rag {

        private int topK = 4;
        private double similarityThreshold = 0.45;
        private String storeFile = "./data/vector-store.json";
        private boolean autoLoad = true;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }

        public String getStoreFile() {
            return storeFile;
        }

        public void setStoreFile(String storeFile) {
            this.storeFile = storeFile;
        }

        public boolean isAutoLoad() {
            return autoLoad;
        }

        public void setAutoLoad(boolean autoLoad) {
            this.autoLoad = autoLoad;
        }
    }

    /** 文生图（通义万相）。 */
    public static class Image {

        private String model = "wanx2.1-t2i-turbo";
        private String size = "1024*1024";

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getSize() {
            return size;
        }

        public void setSize(String size) {
            this.size = size;
        }
    }

    /** Agent 编排：三重预算 + 自检开关 + 轨迹与评测文件位置。 */
    public static class Agent {

        /** 单轮最大决策步数。 */
        private int maxSteps = 6;

        /** 单轮最大工具调用次数（含重试）。 */
        private int maxToolCalls = 4;

        /** 单轮总耗时上限（毫秒），超时后停止循环并如实告知信息不全。 */
        private long deadlineMs = 60000;

        /** 是否对草稿做一次"是否有证据支撑"的自检。 */
        private boolean critic = true;

        /** 轨迹落盘目录。 */
        private String traceDir = "./data/agent-traces";

        /** 内存中保留的轨迹条数。 */
        private int traceCapacity = 200;

        /** 评测用例文件，支持 classpath: 前缀。 */
        private String evalFile = "classpath:agent/eval-cases.jsonl";

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public int getMaxToolCalls() {
            return maxToolCalls;
        }

        public void setMaxToolCalls(int maxToolCalls) {
            this.maxToolCalls = maxToolCalls;
        }

        public long getDeadlineMs() {
            return deadlineMs;
        }

        public void setDeadlineMs(long deadlineMs) {
            this.deadlineMs = deadlineMs;
        }

        public boolean isCritic() {
            return critic;
        }

        public void setCritic(boolean critic) {
            this.critic = critic;
        }

        public String getTraceDir() {
            return traceDir;
        }

        public void setTraceDir(String traceDir) {
            this.traceDir = traceDir;
        }

        public int getTraceCapacity() {
            return traceCapacity;
        }

        public void setTraceCapacity(int traceCapacity) {
            this.traceCapacity = traceCapacity;
        }

        public String getEvalFile() {
            return evalFile;
        }

        public void setEvalFile(String evalFile) {
            this.evalFile = evalFile;
        }
    }
}