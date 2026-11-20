package com.mdkj.agent;

import java.util.List;

/**
 * Agent 回调：让控制层在编排进行中就能把"计划"和"每一步"推给前端，
 * 而不是等整轮跑完才一次性返回（那就失去 Agent 的可解释性了）。
 */
public interface AgentListener {

    AgentListener NOOP = new AgentListener() {
    };

    default void onPlan(List<AgentTask> tasks) {
    }

    default void onStep(AgentStep step) {
    }
}