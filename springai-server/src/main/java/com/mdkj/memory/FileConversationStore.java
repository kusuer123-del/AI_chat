package com.mdkj.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地文件存储：一个会话一个 JSON 文件，重启不丢，适合单机演示。
 */
public class FileConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(FileConversationStore.class);
    private static final TypeReference<List<Turn>> TURN_LIST = new TypeReference<>() {
    };

    private final Path directory;
    private final ObjectMapper objectMapper;

    public FileConversationStore(Path directory, ObjectMapper objectMapper) {
        this.directory = directory;
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("创建会话存储目录失败: " + directory, e);
        }
    }

    @Override
    public List<Turn> load(String conversationId) {
        Path file = fileOf(conversationId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(file.toFile(), TURN_LIST);
        } catch (IOException e) {
            log.warn("[memory] 读取会话文件失败 {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void append(String conversationId, List<Turn> turns) {
        Path file = fileOf(conversationId);
        synchronized (this) {
            List<Turn> all = new ArrayList<>(load(conversationId));
            all.addAll(turns);
            try {
                Files.createDirectories(directory);
                objectMapper.writeValue(file.toFile(), all);
            } catch (IOException e) {
                throw new UncheckedIOException("写入会话文件失败: " + file, e);
            }
        }
    }

    @Override
    public void clear(String conversationId) {
        try {
            Files.deleteIfExists(fileOf(conversationId));
        } catch (IOException e) {
            log.warn("[memory] 删除会话文件失败 {}: {}", fileOf(conversationId), e.getMessage());
        }
    }

    private Path fileOf(String conversationId) {
        return directory.resolve(conversationId.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");
    }
}