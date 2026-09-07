package top.rayawa.monitor.dto;

import top.rayawa.monitor.domain.Alarm;

import java.util.List;

public record AiAnalyzeResponse(List<AiDetectionObject> objects, List<Alarm> alarms) {
}
