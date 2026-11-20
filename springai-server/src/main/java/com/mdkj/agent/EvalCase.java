package com.mdkj.agent;

import java.util.List;

/**
 * 一条评测用例（JSONL 一行一个）。
 *
 * @param id             用例标识
 * @param question       提问
 * @param rag            是否开启检索
 * @param expectContains 答案必须包含的关键词（比较时忽略空白，兼容"7 天"/"7天"）
 * @param expectTool     必须被调用过的工具名
 * @param expectSources  是否必须有知识库召回
 */
public record EvalCase(String id, String question, Boolean rag,
                       List<String> expectContains, String expectTool, Boolean expectSources) {
}