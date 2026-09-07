package top.rayawa.monitor.dto;

import java.util.List;

public record AiDetectionResponse(List<AiDetectionObject> objects) {
    public AiDetectionResponse {
        objects = objects == null ? List.of() : List.copyOf(objects);
    }
}
