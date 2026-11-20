package com.mdkj.rag;

/**
 * 一次检索命中的片段，回传给前端做"可溯源"展示。
 */
public record RetrievedChunk(String title, String snippet, Double score) {
}