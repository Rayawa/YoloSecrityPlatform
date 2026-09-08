package top.rayawa.monitor.service;

import org.springframework.stereotype.Component;
import top.rayawa.monitor.dto.AiDetectionObject;
import top.rayawa.monitor.domain.Alarm;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    /**
     * 物品白名单：命中才算"物品异常"。
     * 未归类到任何集合的类名（traffic light、chair 等）视为目标识别异常，
     * 归入 ignore 桶跳过——不入库、不推送，仅出现在 skipped 清单中。
     */
    private static final Set<String> ITEM_CLASSES = Set.of(
            "suitcase", "handbag", "backpack", "box",          // 行李/包裹（讲义示例 suitcase、box）
            "bowl", "cup", "wine glass", "bottle",             // 容器（讲义示例 bowl、cup）
            "vase", "book", "umbrella",                        // 可遗留物品
            "laptop", "cell phone"                             // 可盗窃财物
    );

    /**
     * 一次识别的映射结果。
     *
     * @param alarms  生成的告警（入库 + WebSocket 推送）
     * @param skipped 目标识别异常被跳过的类名（去重、按出现顺序，仅供识别服务打日志）
     */
    public record MappingResult(List<Alarm> alarms, List<String> skipped) {
        public MappingResult {
            alarms = alarms == null ? List.of() : List.copyOf(alarms);
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
        }
    }

    /**
     * 把识别结果映射为告警列表。
     * 优先级固定：烟火异常 > 人员 > 车辆 > 动物 > 物品；
     * 人员按单帧识别出的 person 实例数定级：≥2 人"人员聚集"(高)，1 人"人员闯入"(中)。
     */
    public MappingResult map(List<AiDetectionObject> detections, String area, String deviceCode, String imageUrl) {
        if (detections == null || detections.isEmpty()) {
            return new MappingResult(List.of(), List.of());
        }

        Map<String, List<AiDetectionObject>> groups = new LinkedHashMap<>();
        LinkedHashSet<String> skipped = new LinkedHashSet<>();
        detections.stream()
                .filter(item -> item.className() != null && !item.className().isBlank())
                .forEach(item -> {
                    String group = category(item.className());
                    if ("ignore".equals(group)) {
                        skipped.add(item.className().toLowerCase(Locale.ROOT));
                    } else {
                        groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(item);
                    }
                });

        List<Alarm> alarms = new ArrayList<>();
        addAlarm(alarms, groups.get("fire"), "烟火异常", "高", area, deviceCode, imageUrl);

        List<AiDetectionObject> people = groups.get("person");
        if (people != null && !people.isEmpty()) {
            String type = people.size() >= 2 ? "人员聚集" : "人员闯入";
            String level = people.size() >= 2 ? "高" : "中";
            addAlarm(alarms, people, type, level, area, deviceCode, imageUrl);
        }

        addAlarm(alarms, groups.get("vehicle"), "车辆异常", "中", area, deviceCode, imageUrl);
        addAlarm(alarms, groups.get("animal"), "动物进入", "中", area, deviceCode, imageUrl);
        addAlarm(alarms, groups.get("item"), "物品异常", "中", area, deviceCode, imageUrl);
        return new MappingResult(alarms, List.copyOf(skipped));
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
        if (ITEM_CLASSES.contains(normalized)) {
            return "item";
        }
        return "ignore";
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
