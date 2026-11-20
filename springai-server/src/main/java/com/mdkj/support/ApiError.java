package com.mdkj.support;

/**
 * 统一错误结构，避免把异常堆栈直接抛给前端。
 */
public record ApiError(String code, String message) {
}