package top.rayawa.monitor.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiDetectionObject(
        @JsonProperty("class") String className,
        double conf,
        String model
) {
}
