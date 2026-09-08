package top.rayawa.monitor.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import top.rayawa.monitor.dto.AiAnalyzeRequest;
import top.rayawa.monitor.dto.AiAnalyzeResponse;
import top.rayawa.monitor.dto.AiDetectionResponse;
import top.rayawa.monitor.domain.Alarm;

import java.util.List;
import java.util.Map;
import java.time.Duration;

@Service
public class AiVisionService {

    private final RestClient restClient;
    private final AlarmService alarmService;

    public AiVisionService(
            @Value("${ai.service-url}") String serviceUrl,
            AlarmService alarmService
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(90));
        this.restClient = RestClient.builder()
                .baseUrl(serviceUrl)
                .requestFactory(requestFactory)
                .build();
        this.alarmService = alarmService;
    }

    public AiAnalyzeResponse analyze(AiAnalyzeRequest request) {
        if (request == null || request.imageUrl() == null || request.imageUrl().isBlank()) {
            throw new IllegalArgumentException("imageUrl 不能为空");
        }

        final AiDetectionResponse detection;
        try {
            detection = restClient.post()
                    .uri("/detect")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("url", request.imageUrl().trim()))
                    .retrieve()
                    .body(AiDetectionResponse.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("AI识别服务不可用，请先启动 ai-service/server.py", e);
        }

        AlarmRuleMapper.MappingResult result = alarmService.createFromDetections(
                detection == null ? List.of() : detection.objects(),
                request.area(),
                request.deviceCode(),
                request.imageUrl()
        );
        return new AiAnalyzeResponse(detection == null ? List.of() : detection.objects(), result.alarms());
    }
}
