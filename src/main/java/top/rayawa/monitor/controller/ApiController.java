package top.rayawa.monitor.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import top.rayawa.monitor.mapper.AlarmMapper;
import top.rayawa.monitor.mapper.DeviceMapper;
import top.rayawa.monitor.dto.AiAnalyzeRequest;
import top.rayawa.monitor.dto.AiAnalyzeResponse;
import top.rayawa.monitor.dto.AiEventRequest;
import top.rayawa.monitor.dto.AiEventResponse;
import top.rayawa.monitor.dto.AiVideoResponse;
import top.rayawa.monitor.domain.Alarm;
import top.rayawa.monitor.domain.Device;
import top.rayawa.monitor.service.AiVisionService;
import top.rayawa.monitor.service.AlarmRuleMapper;
import top.rayawa.monitor.service.AlarmService;
import top.rayawa.monitor.websocket.AlertWebSocketHandler;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final DeviceMapper deviceMapper;
    private final AlarmMapper alarmMapper;
    private final AlarmService alarmService;
    private final AiVisionService aiVisionService;
    private final AlertWebSocketHandler webSocketHandler;
    private final String aiEventToken;

    public ApiController(
            DeviceMapper deviceMapper,
            AlarmMapper alarmMapper,
            AlarmService alarmService,
            AiVisionService aiVisionService,
            AlertWebSocketHandler webSocketHandler,
            @Value("${ai.event-token}") String aiEventToken
    ) {
        this.deviceMapper = deviceMapper;
        this.alarmMapper = alarmMapper;
        this.alarmService = alarmService;
        this.aiVisionService = aiVisionService;
        this.webSocketHandler = webSocketHandler;
        this.aiEventToken = aiEventToken;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("database", "up");
        result.put("deviceCount", deviceMapper.selectCount(null));
        return result;
    }

    @GetMapping("/devices")
    public List<Device> devices(
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String status
    ) {
        QueryWrapper<Device> query = new QueryWrapper<Device>().orderByAsc("id");
        if (area != null && !area.isBlank()) {
            query.eq("area", area.trim());
        }
        if (status != null && !status.isBlank()) {
            query.eq("status", status.trim());
        }
        return deviceMapper.selectList(query);
    }

    @GetMapping("/alarms")
    public List<Alarm> alarms(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source
    ) {
        return alarmService.list(status, source);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        long onlineDevices = deviceMapper.selectCount(
                new QueryWrapper<Device>().eq("status", "在线")
        );
        long alarmCount = alarmMapper.selectCount(null);
        long pendingAlarms = alarmMapper.selectCount(
                new QueryWrapper<Alarm>().eq("status", "待处置")
        );
        long aiEvents = alarmMapper.selectCount(
                new QueryWrapper<Alarm>().eq("source", "AI视觉分析")
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onlineDevices", onlineDevices);
        result.put("deviceCount", deviceMapper.selectCount(null));
        result.put("alarmCount", alarmCount);
        result.put("pendingAlarms", pendingAlarms);
        result.put("aiEvents", aiEvents);
        result.put("webSocketClients", webSocketHandler.onlineSessions());
        return result;
    }

    @PostMapping("/alarms/{id}/process")
    public Alarm processAlarm(@PathVariable long id) {
        return alarmService.process(id);
    }

    @PostMapping("/alarms/{id}/handle")
    public Alarm handleAlarm(@PathVariable long id) {
        return alarmService.handle(id);
    }

    @PostMapping("/ai/analyze")
    public AiAnalyzeResponse analyze(@RequestBody AiAnalyzeRequest request) {
        return aiVisionService.analyze(request);
    }

    @PostMapping(value = "/ai/analyze-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AiAnalyzeResponse analyzeImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String deviceCode
    ) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要识别的图片文件");
        }
        if (file.getSize() > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("图片不能超过 20MB");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!(filename.endsWith(".jpg") || filename.endsWith(".jpeg") || filename.endsWith(".png"))) {
            throw new IllegalArgumentException("图片仅支持 JPG、JPEG、PNG 格式");
        }
        return aiVisionService.analyzeImage(file, area, deviceCode);
    }

    @PostMapping(value = "/ai/analyze-video", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AiVideoResponse analyzeVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String deviceCode
    ) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要识别的视频文件");
        }
        if (file.getSize() > 50L * 1024 * 1024) {
            throw new IllegalArgumentException("视频不能超过 50MB");
        }
        return aiVisionService.analyzeVideo(file, area, deviceCode);
    }

    @PostMapping("/ai/event")
    public AiEventResponse receiveAiEvent(
            @RequestHeader(value = "X-AI-Event-Token", required = false) String token,
            @RequestBody AiEventRequest request
    ) {
        if (token == null || !MessageDigest.isEqual(
                aiEventToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new SecurityException("AI 事件令牌无效");
        }
        AlarmRuleMapper.MappingResult result = alarmService.createFromDetections(
                request.objects(), request.area(), request.deviceCode(), request.image(), request.annotated()
        );
        return new AiEventResponse(result.alarms(), result.skipped());
    }
}
