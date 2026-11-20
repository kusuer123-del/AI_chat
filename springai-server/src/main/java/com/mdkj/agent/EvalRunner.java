package com.mdkj.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mdkj.config.AiProperties;
import com.mdkj.dto.AgentRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 评测器：跑固定用例集，按"关键词 / 工具调用 / 召回"三项硬指标判定，输出通过率与平均步数。
 *
 * <p>用例是 JSONL，放 resources/agent/eval-cases.jsonl，也可以指向外部文件路径。
 * 判定刻意做成确定性的（字符串包含、轨迹检查），不引入 LLM 当裁判，避免"评测本身不稳定"。</p>
 */
@Component
public class EvalRunner {

    private final AgentOrchestrator orchestrator;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    public EvalRunner(AgentOrchestrator orchestrator, ObjectMapper objectMapper, AiProperties properties) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<EvalCase> cases() {
        String location = properties.getAgent().getEvalFile();
        try (BufferedReader reader = open(location)) {
            List<EvalCase> cases = new ArrayList<>();
            for (String line : reader.lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                try {
                    cases.add(objectMapper.readValue(trimmed, EvalCase.class));
                } catch (Exception ex) {
                    throw new IllegalStateException("用例格式错误：" + trimmed, ex);
                }
            }
            return cases;
        } catch (IOException ex) {
            throw new IllegalStateException("评测用例加载失败：" + location, ex);
        }
    }

    public EvalReport run(int limit) {
        List<EvalCase> all = cases();
        int size = limit <= 0 ? Math.min(5, all.size()) : Math.min(limit, all.size());
        List<EvalReport.CaseResult> results = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            EvalCase testCase = all.get(i);
            long started = System.currentTimeMillis();
            try {
                AgentResult result = orchestrator.run(new AgentRequest(
                        "eval-" + testCase.id(),
                        testCase.question(),
                        testCase.rag() == null || testCase.rag(),
                        null));
                results.add(judge(testCase, result));
            } catch (Exception ex) {
                results.add(new EvalReport.CaseResult(testCase.id(), testCase.question(), false,
                        List.of("执行异常：" + ex.getMessage()), "", 0,
                        System.currentTimeMillis() - started));
            }
        }

        int passed = (int) results.stream().filter(EvalReport.CaseResult::passed).count();
        double avgSteps = results.stream().mapToInt(EvalReport.CaseResult::steps).average().orElse(0);
        long avgCost = (long) results.stream().mapToLong(EvalReport.CaseResult::costMs).average().orElse(0);
        double passRate = results.isEmpty() ? 0 : (double) passed / results.size();
        return new EvalReport(results.size(), passed, passRate, avgSteps, avgCost, results);
    }

    private EvalReport.CaseResult judge(EvalCase testCase, AgentResult result) {
        List<String> reasons = new ArrayList<>();
        String answer = result.trace().getAnswer() == null ? "" : result.trace().getAnswer();
        String normalized = normalize(answer);

        if (testCase.expectContains() != null) {
            for (String expected : testCase.expectContains()) {
                if (!normalized.contains(normalize(expected))) {
                    reasons.add("答案缺少关键词「" + expected + "」");
                }
            }
        }
        if (testCase.expectTool() != null && !testCase.expectTool().isBlank()) {
            boolean used = result.trace().getSteps().stream()
                    .anyMatch(step -> AgentStep.TOOL.equals(step.type()) && step.title().contains(testCase.expectTool()));
            if (!used) {
                reasons.add("未调用工具 " + testCase.expectTool());
            }
        }
        if (Boolean.TRUE.equals(testCase.expectSources()) && result.sources().isEmpty()) {
            reasons.add("未召回知识库片段");
        }

        return new EvalReport.CaseResult(testCase.id(), testCase.question(), reasons.isEmpty(), reasons,
                truncate(answer, 300), result.trace().getSteps().size(), result.trace().getCostMs());
    }

    private BufferedReader open(String location) throws IOException {
        if (location.startsWith("classpath:")) {
            ClassPathResource resource = new ClassPathResource(
                    location.substring("classpath:".length()));
            return new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
        }
        return Files.newBufferedReader(Path.of(location), StandardCharsets.UTF_8);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\u3000]+", "");
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 仅用于调试：确认用例能被正确解析。 */
    public Optional<EvalCase> first() {
        return cases().stream().findFirst();
    }
}