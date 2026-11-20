package com.mdkj.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 天气工具：用 @Tool / @ToolParam 声明式注册，由模型自行判断是否需要调用、抽取城市与日期参数。
 * 数据是 mock 的，重点是把"意图识别 → 工具选择 → 参数抽取 → 结果整合"这条闭环跑通。
 */
@Component
public class WeatherTools {

    private static final List<String> CONDITIONS = List.of("晴", "多云", "小雨", "阴", "阵雪");

    @Tool(name = "getWeather",
            description = "查询指定城市的天气，返回温度区间、天气状况与穿衣建议。用户问天气、气温、是否需要带伞、是否适合出行时调用。")
    public String getWeather(
            @ToolParam(description = "中文城市名，例如：哈尔滨、北京、上海") String city,
            @ToolParam(description = "查询日期，格式 yyyy-MM-dd，可为空，为空表示今天", required = false) String date) {

        LocalDate day = (date == null || date.isBlank()) ? LocalDate.now() : LocalDate.parse(date);
        int seed = Math.abs((city + day).hashCode());
        String condition = CONDITIONS.get(seed % CONDITIONS.size());
        int high = 6 + seed % 12;
        int low = high - 6 - seed % 5;

        String advice = switch (condition) {
            case "小雨" -> "建议带伞，路面湿滑注意安全";
            case "阵雪" -> "路面可能结冰，建议穿防滑鞋并注意保暖";
            case "晴" -> "适合外出，注意防晒";
            default -> "气温偏凉，建议加一件外套";
        };

        return String.format("%s %s 天气：%s，气温 %d℃ ~ %d℃，%s。", city, day, condition, low, high, advice);
    }
}