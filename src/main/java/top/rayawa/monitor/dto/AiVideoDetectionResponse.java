package top.rayawa.monitor.dto;

import java.util.List;

/**
 * AI 服务 /detect/video 的响应体。
 *
 * @param totalFrames 视频总帧数
 * @param frames      抽帧识别结果
 */
public record AiVideoDetectionResponse(int totalFrames, List<AiVideoFrame> frames) {
    public AiVideoDetectionResponse {
        frames = frames == null ? List.of() : List.copyOf(frames);
    }
}
