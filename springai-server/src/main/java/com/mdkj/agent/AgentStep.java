package com.mdkj.agent;

/**
 * Agent 执行过程中的一个步骤，用于前端"思考过程"展示与事后回放。
 *
 * @param index  步骤序号，从 1 开始
 * @param type   plan / think / tool / observe / critic / error / final
 * @param title  一句话标题，直接展示给用户
 * @param detail 补充说明（决策理由、命中数量等）
 * @param input  该步骤的输入（工具参数、待校验文本）
 * @param output 该步骤的输出（工具结果、校验意见）
 * @param costMs 该步骤耗时
 */
public record AgentStep(int index, String type, String title, String detail,
                        String input, String output, long costMs) {

    public static final String PLAN = "plan";
    public static final String THINK = "think";
    public static final String TOOL = "tool";
    public static final String OBSERVE = "observe";
    public static final String CRITIC = "critic";
    public static final String ERROR = "error";
}