package com.mdkj.agent;

import java.util.List;

/**
 * 评测报告：通过率、平均步数、平均耗时，以及每条失败原因。
 *
 * <p>"能报出成功率和平均步数"是 Agent 项目与 demo 的分界线——
 * 没有它，所有效果描述都只能靠形容词。</p>
 */
public record EvalReport(int total, int passed, double passRate,
                         double avgSteps, long avgCostMs, List<CaseResult> cases) {

    public record CaseResult(String id, String question, boolean passed, List<String> reasons,
                             String answer, int steps, long costMs) {
    }
}