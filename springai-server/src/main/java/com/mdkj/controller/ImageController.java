package com.mdkj.controller;

import com.mdkj.config.AiProperties;
import com.mdkj.dto.ImageReply;
import com.mdkj.dto.ImageRequest;
import com.alibaba.cloud.ai.dashscope.image.DashScopeImageOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文生图：通义万相（DashScope ImageModel）。
 */
@RestController
@RequestMapping("/api/v1/image")
@CrossOrigin
public class ImageController {

    private static final Logger log = LoggerFactory.getLogger(ImageController.class);

    private final ImageModel imageModel;
    private final AiProperties properties;

    public ImageController(ImageModel imageModel, AiProperties properties) {
        this.imageModel = imageModel;
        this.properties = properties;
    }

    @PostMapping
    public ImageReply generate(@RequestBody ImageRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        String prompt = request.prompt().trim();
        String size = request.size() == null || request.size().isBlank()
                ? properties.getImage().getSize() : request.size().trim();
        String model = properties.getImage().getModel();

        DashScopeImageOptions options = DashScopeImageOptions.builder()
                .withModel(model)
                .withN(1)
                .build();
        options.setSize(size);

        long start = System.currentTimeMillis();
        ImageResponse response = imageModel.call(new ImagePrompt(prompt, options));
        long cost = System.currentTimeMillis() - start;

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("文生图未返回结果");
        }
        Image image = response.getResult().getOutput();
        log.info("[image] model={} size={} cost={}ms url={} b64={}",
                model, size, cost, image.getUrl(), image.getB64Json() != null);

        return new ImageReply(prompt, size, model, image.getUrl(), image.getB64Json());
    }
}