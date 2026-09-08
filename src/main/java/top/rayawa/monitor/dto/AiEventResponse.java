package top.rayawa.monitor.dto;

import top.rayawa.monitor.domain.Alarm;

import java.util.List;

/**
 * /api/ai/event 的响应体。
 *
 * @param alarms  本次识别生成的告警（已入库并推送）
 * @param skipped 目标识别异常被跳过的类名清单（供识别服务打日志）
 */
public record AiEventResponse(List<Alarm> alarms, List<String> skipped) {
    public AiEventResponse {
        alarms = alarms == null ? List.of() : List.copyOf(alarms);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }
}
