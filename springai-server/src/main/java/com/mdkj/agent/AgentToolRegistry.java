package com.mdkj.agent;

import com.mdkj.tool.ExpressTools;
import com.mdkj.tool.WeatherTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表。
 *
 * <p>工具仍然用 {@code @Tool} 声明（单一事实来源），这里只是把 Spring AI 的 ToolCallback 收进 Map，
 * 好让编排器按名字<b>显式</b>调用——单跳工具调用由框架驱动，多步循环必须由我们自己的代码驱动。</p>
 */
@Component
public class AgentToolRegistry {

    private final Map<String, ToolCallback> callbacks = new LinkedHashMap<>();

    public AgentToolRegistry(WeatherTools weatherTools, ExpressTools expressTools) {
        for (ToolCallback callback : ToolCallbacks.from(weatherTools, expressTools)) {
            callbacks.put(callback.getToolDefinition().name(), callback);
        }
    }

    public List<ToolSpec> specs() {
        return callbacks.values().stream()
                .map(callback -> new ToolSpec(
                        callback.getToolDefinition().name(),
                        callback.getToolDefinition().description(),
                        callback.getToolDefinition().inputSchema()))
                .toList();
    }

    public boolean has(String name) {
        return name != null && callbacks.containsKey(name);
    }

    public String invoke(String name, String jsonArguments) {
        ToolCallback callback = callbacks.get(name);
        if (callback == null) {
            throw new IllegalArgumentException("未注册的工具：" + name);
        }
        return callback.call(jsonArguments == null || jsonArguments.isBlank() ? "{}" : jsonArguments);
    }
}