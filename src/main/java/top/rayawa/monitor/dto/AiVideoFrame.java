package top.rayawa.monitor.dto;

import java.util.List;

/**
 * 视频识别中一帧的结果。
 *
 * @param frame     帧序号（视频中的帧位置）
 * @param objects   该帧识别出的对象列表
 * @param annotated 该帧带检测框的标注图（JPEG base64）
 */
public record AiVideoFrame(int frame, List<AiDetectionObject> objects, String annotated) {
    public AiVideoFrame {
        objects = objects == null ? List.of() : List.copyOf(objects);
    }
}
