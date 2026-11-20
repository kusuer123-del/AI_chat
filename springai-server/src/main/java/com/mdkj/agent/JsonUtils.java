package com.mdkj.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * 模型输出的 JSON 容错解析。
 *
 * <p>模型很爱在 JSON 外面套 ```json 代码块，或者前后加一句"好的，这是结果："，
 * 直接 readTree 会直接抛异常。这里先剥掉代码块，再截取第一个 '{' 到最后一个 '}'，
 * 解析失败返回 empty，让调用方走降级分支而不是让整个请求 500。</p>
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtils() {
    }

    public static Optional<JsonNode> parseObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = stripFence(raw.trim());
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String body = text.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        return closing >= 0 ? body.substring(0, closing) : body;
    }
}