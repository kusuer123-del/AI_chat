package com.mdkj.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mdkj.config.AiProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 服务：文档切片入库（EmbeddingModel + 向量库）、相似度检索、整篇删除。
 *
 * <p>知识边界从"模型预训练"迁到"可控知识库"：业务知识更新只需要替换文档重建索引，不用重训模型；
 * 检索命中的片段会随回答一起回传前端，回答可回溯到具体文档。</p>
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final String BUILTIN_PATTERN = "classpath*:docs/*.md";
    private static final TypeReference<List<KnowledgeDocument>> DOC_LIST = new TypeReference<>() {
    };

    private final SimpleVectorStore vectorStore;
    private final AiProperties properties;
    private final ResourcePatternResolver resourcePatternResolver;
    private final ObjectMapper objectMapper;
    private final TokenTextSplitter splitter = new TokenTextSplitter();
    private final Map<String, KnowledgeDocument> registry = new ConcurrentHashMap<>();
    private final Path storeFile;
    private final Path metaFile;
    private final String apiKey;

    public RagService(SimpleVectorStore vectorStore,
                      AiProperties properties,
                      ResourcePatternResolver resourcePatternResolver,
                      ObjectMapper objectMapper,
                      @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.resourcePatternResolver = resourcePatternResolver;
        this.objectMapper = objectMapper;
        this.storeFile = Path.of(properties.getRag().getStoreFile());
        this.metaFile = Path.of(properties.getRag().getStoreFile() + ".meta.json");
        this.apiKey = apiKey;
    }

    @PostConstruct
    public void init() {
        restore();
        if (properties.getRag().isAutoLoad() && registry.isEmpty()) {
            loadBuiltinDocuments();
        }
    }

    /** 文本入库，返回切片数。 */
    public DocumentReply ingest(String title, String content) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("文档标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("文档内容不能为空");
        }

        Document root = Document.builder()
                .id(UUID.randomUUID().toString())
                .text(content)
                .metadata(Map.of("title", title))
                .build();
        List<Document> chunks = splitter.apply(List.of(root));

        List<String> chunkIds = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>(chunk.getMetadata());
            metadata.put("title", title);
            metadata.put("chunkIndex", i);
            Document tagged = new Document(chunk.getId(), chunk.getText(), metadata);
            chunks.set(i, tagged);
            chunkIds.add(tagged.getId());
        }

        vectorStore.add(chunks);
        KnowledgeDocument document = new KnowledgeDocument(
                title, chunks.size(), chunkIds, LocalDateTime.now().withNano(0).toString());
        registry.put(title, document);
        persist();

        log.info("[rag] 入库《{}》，切片 {} 条，当前共 {} 篇", title, chunks.size(), registry.size());
        return new DocumentReply(title, chunks.size(), registry.size(), totalChunks());
    }

    public List<KnowledgeDocument> documents() {
        return List.copyOf(registry.values());
    }

    public int clear() {
        int removed = registry.size();
        for (KnowledgeDocument document : registry.values()) {
            if (!document.chunkIds().isEmpty()) {
                vectorStore.delete(document.chunkIds());
            }
        }
        registry.clear();
        persist();
        log.info("[rag] 已清空知识库，共删除 {} 篇", removed);
        return removed;
    }

    /** 检索：命中片段回传前端，同时拼成提示词上下文。 */
    public List<RetrievedChunk> search(String query) {
        return search(query, properties.getRag().getTopK(), properties.getRag().getSimilarityThreshold());
    }

    public List<RetrievedChunk> search(String query, int topK, double similarityThreshold) {
        if (query == null || query.isBlank() || registry.isEmpty()) {
            return List.of();
        }
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(Math.max(1, topK))
                .similarityThreshold(similarityThreshold)
                .build();
        return vectorStore.similaritySearch(request).stream()
                .map(doc -> new RetrievedChunk(
                        String.valueOf(doc.getMetadata().getOrDefault("title", "未知文档")),
                        snippet(doc.getText()),
                        doc.getScore()))
                .toList();
    }

    /** 把命中片段拼成提示词上下文（供控制层注入 user 消息）。 */
    public static String formatContext(List<RetrievedChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            builder.append('[').append(i + 1).append("] 《").append(chunks.get(i).title()).append("》\n")
                    .append(chunks.get(i).snippet()).append("\n\n");
        }
        return builder.toString().stripTrailing();
    }

    public int totalChunks() {
        return registry.values().stream().mapToInt(KnowledgeDocument::chunkCount).sum();
    }

    private void loadBuiltinDocuments() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[rag] 未配置 DASHSCOPE_API_KEY，跳过内置知识库加载（配置后重启或调用 /api/v1/rag/documents 手动入库）");
            return;
        }
        try {
            for (Resource resource : resourcePatternResolver.getResources(BUILTIN_PATTERN)) {
                if (!resource.isReadable()) {
                    continue;
                }
                String title = resource.getFilename() == null ? "内置文档" : resource.getFilename();
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                try {
                    ingest(title, content);
                } catch (RuntimeException ex) {
                    log.warn("[rag] 内置文档《{}》入库失败: {}", title, ex.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[rag] 扫描内置知识库失败: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            Path parent = storeFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            vectorStore.save(storeFile.toFile());
            objectMapper.writeValue(metaFile.toFile(), registry.values());
        } catch (IOException e) {
            log.warn("[rag] 向量库落盘失败: {}", e.getMessage());
        }
    }

    private void restore() {
        if (!Files.exists(storeFile) || !Files.exists(metaFile)) {
            return;
        }
        try {
            vectorStore.load(storeFile.toFile());
            List<KnowledgeDocument> documents = objectMapper.readValue(metaFile.toFile(), DOC_LIST);
            documents.forEach(document -> registry.put(document.title(), document));
            log.info("[rag] 已从 {} 恢复 {} 篇知识文档、{} 个切片", storeFile, registry.size(), totalChunks());
        } catch (IOException | UncheckedIOException e) {
            log.warn("[rag] 恢复向量库失败，按空库启动: {}", e.getMessage());
            registry.clear();
        }
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= 160 ? single : single.substring(0, 160) + "…";
    }

    /** 入库结果。 */
    public record DocumentReply(String title, int chunkCount, int totalDocuments, int totalChunks) {
    }
}