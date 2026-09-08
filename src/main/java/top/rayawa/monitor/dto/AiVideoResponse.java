package top.rayawa.monitor.dto;

import top.rayawa.monitor.domain.Alarm;

import java.util.List;

/**
 * /api/ai/analyze-video 的响应体。
 *
 * @param frames  抽帧识别结果（含带框图）
 * @param alarms  汇总去重后生成的告警
 * @param skipped 目标识别异常被跳过的类名清单
 */
public record AiVideoResponse(List<AiVideoFrame> frames, List<Alarm> alarms, List<String> skipped) {
    public AiVideoResponse {
        frames = frames == null ? List.of() : List.copyOf(frames);
        alarms = alarms == null ? List.of() : List.copyOf(alarms);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }
}
