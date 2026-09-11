package top.rayawa.monitor.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import top.rayawa.monitor.dto.AiAnalyzeRequest;
import top.rayawa.monitor.dto.AiAnalyzeResponse;
import top.rayawa.monitor.dto.AiDetectionObject;
import top.rayawa.monitor.dto.AiDetectionResponse;
import top.rayawa.monitor.dto.AiVideoDetectionResponse;
import top.rayawa.monitor.dto.AiVideoFrame;
import top.rayawa.monitor.dto.AiVideoResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        requestFactory.setReadTimeout(Duration.ofSeconds(180));
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

        List<AiDetectionObject> objects = detection == null ? List.of() : detection.objects();
        String annotated = detection == null ? null : detection.annotated();
        AlarmRuleMapper.MappingResult result = alarmService.createFromDetections(
                objects, request.area(), request.deviceCode(), request.imageUrl(), annotated
        );
        return new AiAnalyzeResponse(objects, result.alarms(), annotated);
    }

    public AiAnalyzeResponse analyzeImage(MultipartFile file, String area, String deviceCode) {
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());

        final AiDetectionResponse detection;
        try {
            detection = restClient.post()
                    .uri("/detect/upload")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(AiDetectionResponse.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("AI识别服务不可用，请先启动 ai-service/server.py", e);
        }

        List<AiDetectionObject> objects = detection == null ? List.of() : detection.objects();
        String annotated = detection == null ? null : detection.annotated();
        AlarmRuleMapper.MappingResult result = alarmService.createFromDetections(
                objects, area, deviceCode, file.getOriginalFilename(), annotated
        );
        return new AiAnalyzeResponse(objects, result.alarms(), annotated);
    }

    public AiVideoResponse analyzeVideo(MultipartFile file, String area, String deviceCode) {
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());
        body.add("max_frames", "5");

        final AiVideoDetectionResponse video;
        try {
            video = restClient.post()
                    .uri("/detect/video")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(AiVideoDetectionResponse.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("AI识别服务不可用，请先启动 ai-service/server.py", e);
        }

        List<AiVideoFrame> frames = video == null ? List.of() : video.frames();
        // 汇总所有帧的对象：同 (类名, 模型) 只保留置信度最高的一次，避免同一目标多帧重复告警
        Map<String, AiDetectionObject> merged = new LinkedHashMap<>();
        frames.forEach(frame -> frame.objects().forEach(obj -> {
            String key = obj.className().toLowerCase(Locale.ROOT) + "|" + obj.model();
            merged.merge(key, obj, (existing, incoming) -> existing.conf() >= incoming.conf() ? existing : incoming);
        }));
        AlarmRuleMapper.MappingResult result = alarmService.createFromDetections(
                new ArrayList<>(merged.values()), area, deviceCode, file.getOriginalFilename(),
                video == null ? null : video.composite()
        );
        return new AiVideoResponse(frames, result.alarms(), result.skipped());
    }
}
