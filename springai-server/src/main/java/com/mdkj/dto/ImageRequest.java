package com.mdkj.dto;

/**
 * 文生图请求。
 *
 * @param prompt 画面描述
 * @param size   输出尺寸，如 1024*1024；为空取配置默认值
 */
public record ImageRequest(String prompt, String size) {
}