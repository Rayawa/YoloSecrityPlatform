package top.rayawa.monitor.service;

import org.springframework.stereotype.Component;
import top.rayawa.monitor.dto.AiDetectionObject;
import top.rayawa.monitor.domain.Alarm;

import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AlarmRuleMapper {

    private static final Set<String> FIRE_CLASSES = Set.of("fire", "smoke", "flame");
    private static final Set<String> VEHICLE_CLASSES = Set.of(
            "car", "truck", "bus", "motorcycle", "motorbike", "bicycle", "train"
    );
    private static final Set<String> ANIMAL_CLASSES = Set.of(
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe"
    );

    public List<Alarm> map(List<AiDetectionObject> detections, String area, String deviceCode, String imageUrl) {
        if (detections == null || detections.isEmpty()) {
            return List.of();
        }

        Map<String, List<AiDetectionObject>> groups = new LinkedHashMap<>();
        detections.stream()
                .filter(item -> item.className() != null && !item.className().isBlank())
                .forEach(item -> groups.computeIfAbsent(category(item.className()), ignored -> new ArrayList<>()).add(item));

        List<Alarm> alarms = new ArrayList<>();
        addAlarm(alarms, groups.get("fire"), "烟火异常", "高", area, deviceCode, imageUrl);

        List<AiDetectionObject> people = groups.get("person");
        if (people != null && !people.isEmpty()) {
            String type = people.size() >= 3 ? "人员聚集" : "人员闯入";
            String level = people.size() >= 3 ? "中" : "高";
            addAlarm(alarms, people, type, level, area, deviceCode, imageUrl);
        }

        addAlarm(alarms, groups.get("vehicle"), "车辆异常", "中", area, deviceCode, imageUrl);
        addAlarm(alarms, groups.get("animal"), "动物进入", "中", area, deviceCode, imageUrl);
        addAlarm(alarms, groups.get("object"), "物品异常", "低", area, deviceCode, imageUrl);
        return alarms;
    }

    private String category(String className) {
        String normalized = className.toLowerCase(Locale.ROOT);
        if (FIRE_CLASSES.contains(normalized)) {
            return "fire";
        }
        if ("person".equals(normalized)) {
            return "person";
        }
        if (VEHICLE_CLASSES.contains(normalized)) {
            return "vehicle";
        }
        if (ANIMAL_CLASSES.contains(normalized)) {
            return "animal";
        }
        return "object";
    }

    private void addAlarm(
            List<Alarm> result,
            List<AiDetectionObject> detections,
            String type,
            String level,
            String area,
            String deviceCode,
            String imageUrl
    ) {
        if (detections == null || detections.isEmpty()) {
            return;
        }

        Alarm alarm = new Alarm();
        alarm.setType(type);
        alarm.setArea(area == null || area.isBlank() ? "未指定区域" : area.trim());
        alarm.setLevel(level);
        alarm.setStatus("待处置");
        alarm.setSource("AI视觉分析");
        alarm.setEventTime(new Date());

        String objects = detections.stream()
                .map(item -> "%s(%.0f%%)".formatted(item.className(), item.conf() * 100))
                .collect(Collectors.joining("、"));
        StringBuilder detail = new StringBuilder("识别结果：").append(objects);
        if (deviceCode != null && !deviceCode.isBlank()) {
            detail.append("；设备：").append(deviceCode.trim());
        }
        if (imageUrl != null && !imageUrl.isBlank()) {
            detail.append("；图片：").append(imageUrl.trim());
        }
        String detailText = detail.toString();
        alarm.setDetail(detailText.length() <= 500 ? detailText : detailText.substring(0, 500));
        result.add(alarm);
    }
}
