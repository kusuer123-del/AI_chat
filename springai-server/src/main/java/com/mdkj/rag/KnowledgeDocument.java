package com.mdkj.rag;

import java.util.List;

/**
 * 已入库的知识文档（按切片记账，方便整篇删除）。
 */
public record KnowledgeDocument(String title, int chunkCount, List<String> chunkIds, String createdAt) {
}