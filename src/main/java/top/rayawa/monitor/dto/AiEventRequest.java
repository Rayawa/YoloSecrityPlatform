package top.rayawa.monitor.dto;

import java.util.List;

/**
 * 识别服务推送识别结果的请求体（讲义接口 /api/ai/event）。
 *
 * @param image      图片文件名或标识（识别服务推送 ai-watch 中的文件名）
 * @param area       区域（可选，缺省由规则层填"未指定区域"）
 * @param deviceCode 设备编号（可选）
 * @param objects    识别出的对象列表
 */
public record AiEventRequest(
        String image,
        String area,
        String deviceCode,
        List<AiDetectionObject> objects
) {
    public AiEventRequest {
        objects = objects == null ? List.of() : List.copyOf(objects);
    }
}
