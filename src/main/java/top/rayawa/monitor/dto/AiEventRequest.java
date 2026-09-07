package top.rayawa.monitor.dto;

import java.util.List;

public record AiEventRequest(
        String area,
        String deviceCode,
        String imageUrl,
        List<AiDetectionObject> objects
) {
    public AiEventRequest {
        objects = objects == null ? List.of() : List.copyOf(objects);
    }
}
