package com.mdkj.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 快递查询工具：演示第二个工具，验证模型能在多个工具间做选择。
 */
@Component
public class ExpressTools {

    @Tool(name = "queryExpress",
            description = "根据快递单号查询物流状态，返回当前节点、所在城市与预计送达时间。用户问快递到哪了、什么时候能到、有没有签收时调用。")
    public String queryExpress(
            @ToolParam(description = "快递单号，纯数字或字母组合，例如：SF1234567890") String trackingNo) {

        if (trackingNo == null || trackingNo.isBlank()) {
            return "快递单号为空，无法查询，请向用户索取单号。";
        }
        int seed = Math.abs(trackingNo.hashCode());
        String[] cities = {"哈尔滨转运中心", "沈阳分拣中心", "北京朝阳分部", "派送中"};
        String[] nodes = {"已揽收", "运输中", "到达派送点", "快递员正在派送"};
        int index = seed % cities.length;

        return String.format("单号 %s：当前状态「%s」，位置 %s，预计 %d 天内送达。",
                trackingNo, nodes[index], cities[index], 1 + seed % 3);
    }
}