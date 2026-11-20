package com.mdkj.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mdkj.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 轨迹存储：内存 LRU + 落盘 JSON。
 *
 * <p>落盘是为了"事后回放"——面试或排障时可以直接看某次执行每一跳的输入输出，
 * 而不是只有一句最终答案。用文件而不是数据库，是为了不引入额外依赖。</p>
 */
@Component
public class AgentTraceStore {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceStore.class);

    private final ObjectMapper objectMapper;
    private final Path directory;
    private final int capacity;
    private final Map<String, AgentTrace> memory;

    public AgentTraceStore(ObjectMapper objectMapper, AiProperties properties) {
        this.objectMapper = objectMapper;
        this.directory = Path.of(properties.getAgent().getTraceDir());
        this.capacity = Math.max(16, properties.getAgent().getTraceCapacity());
        this.memory = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, AgentTrace> eldest) {
                return size() > capacity;
            }
        });
    }

    public void save(AgentTrace trace) {
        if (trace == null || trace.getTraceId() == null) {
            return;
        }
        memory.put(trace.getTraceId(), trace);
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(trace.getTraceId() + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), trace);
        } catch (IOException ex) {
            // 落盘失败不影响主流程：内存里还有一份
            log.warn("[agent] 轨迹落盘失败 trace={}: {}", trace.getTraceId(), ex.getMessage());
        }
    }

    public Optional<AgentTrace> find(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        AgentTrace cached = memory.get(traceId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path file = directory.resolve(traceId + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), AgentTrace.class));
        } catch (IOException ex) {
            log.warn("[agent] 轨迹读取失败 {}: {}", file, ex.getMessage());
            return Optional.empty();
        }
    }

    /** 最近的轨迹摘要，按时间倒序。 */
    public List<Map<String, Object>> recent(int limit) {
        List<AgentTrace> traces = new ArrayList<>(memory.values());
        traces.sort((a, b) -> Long.compare(b.getStartedAt(), a.getStartedAt()));
        return traces.stream()
                .limit(Math.max(1, limit))
                .map(trace -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("traceId", trace.getTraceId());
                    item.put("question", trace.getQuestion());
                    item.put("status", trace.getStatus());
                    item.put("success", trace.isSuccess());
                    item.put("steps", trace.getSteps().size());
                    item.put("toolCalls", trace.getToolCalls());
                    item.put("costMs", trace.getCostMs());
                    item.put("startedAt", trace.getStartedAt());
                    return item;
                })
                .toList();
    }
}