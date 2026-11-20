package com.mdkj.agent;

import java.util.Map;

/**
 * 一次决策的输出：调工具、给出草稿答案，或主动收敛。
 *
 * @param type      tool / answer / finish
 * @param goal      对应哪个子任务
 * @param tool      工具名（type=tool 时有效）
 * @param arguments 工具参数
 * @param answer    草稿答案（type=answer 时有效）
 * @param rationale 决策理由，用于轨迹回放
 */
public record AgentAction(String type, String goal, String tool,
                          Map<String, Object> arguments, String answer, String rationale) {

    public static final String TYPE_TOOL = "tool";
    public static final String TYPE_ANSWER = "answer";
    public static final String TYPE_FINISH = "finish";
}