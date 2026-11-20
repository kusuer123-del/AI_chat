package com.mdkj.dto;

/**
 * 文生图结果：url 与 b64 二选一，取决于模型返回格式。
 */
public record ImageReply(String prompt, String size, String model, String url, String b64Json) {
}