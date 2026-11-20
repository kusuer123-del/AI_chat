package com.mdkj;

import com.mdkj.config.AiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * AI 增强型多场景智能客服 · 启动类。
 *
 * <p>能力线：多轮对话（ChatMemory）、RAG 知识库问答、Function Calling 工具、文生图、审计日志。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(AiProperties.class)
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}