package com.mdkj.agent;

/**
 * 暴露给模型看的工具描述（名字 / 说明 / JSON Schema）。
 */
public record ToolSpec(String name, String description, String schema) {
}