package com.mdkj.memory;

/**
 * 一条对话记录（只存角色与文本，够 ChatMemory 用；工具调用等结构不落库）。
 */
public record Turn(String role, String text) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
}