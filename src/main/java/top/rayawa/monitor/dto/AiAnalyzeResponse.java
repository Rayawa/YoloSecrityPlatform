package top.rayawa.monitor.dto;

import top.rayawa.monitor.domain.Alarm;

import java.util.List;

/**
 * /api/ai/analyze 的响应体。
 *
 * @param objects   识别出的对象列表（回显给前端）
 * @param alarms    本次识别生成的告警
 * @param annotated 带检测框的标注图（JPEG base64，可空）
 */
public record AiAnalyzeResponse(List<AiDetectionObject> objects, List<Alarm> alarms, String annotated) {
    public AiAnalyzeResponse {
        objects = objects == null ? List.of() : List.copyOf(objects);
        alarms = alarms == null ? List.of() : List.copyOf(alarms);
    }
}
