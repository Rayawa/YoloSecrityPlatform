package top.rayawa.monitor.dto;

import java.util.List;

/**
 * AI 服务识别接口的响应体。
 *
 * @param objects   识别出的对象列表
 * @param annotated 带检测框的标注图（JPEG base64，可空）
 */
public record AiDetectionResponse(List<AiDetectionObject> objects, String annotated) {
    public AiDetectionResponse {
        objects = objects == null ? List.of() : List.copyOf(objects);
    }
}
