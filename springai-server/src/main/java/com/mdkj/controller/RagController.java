package com.mdkj.controller;

import com.mdkj.dto.DocumentRequest;
import com.mdkj.rag.KnowledgeDocument;
import com.mdkj.rag.RagService;
import com.mdkj.rag.RetrievedChunk;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库维护：入库、列表、清空、检索预览。
 */
@RestController
@RequestMapping("/api/v1/rag")
@CrossOrigin
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/documents")
    public RagService.DocumentReply ingest(@RequestBody DocumentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return ragService.ingest(request.title(), request.content());
    }

    @GetMapping("/documents")
    public Map<String, Object> documents() {
        List<KnowledgeDocument> documents = ragService.documents();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documents", documents);
        payload.put("totalDocuments", documents.size());
        payload.put("totalChunks", ragService.totalChunks());
        return payload;
    }

    @DeleteMapping("/documents")
    public Map<String, Object> clear() {
        int removed = ragService.clear();
        return Map.of("removedDocuments", removed);
    }

    /** 检索预览：不调用大模型，只看召回，便于定位偏差来自召回还是生成。 */
    @GetMapping("/search")
    public List<RetrievedChunk> search(@RequestParam("query") String query,
                                       @RequestParam(value = "topK", defaultValue = "4") int topK) {
        return ragService.search(query, topK, 0.0d);
    }
}