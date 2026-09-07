package top.rayawa.monitor.service;

import org.junit.jupiter.api.Test;
import top.rayawa.monitor.dto.AiDetectionObject;
import top.rayawa.monitor.domain.Alarm;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlarmRuleMapperTest {

    private final AlarmRuleMapper mapper = new AlarmRuleMapper();

    @Test
    void mapsSecurityObjectsByPriorityAndCategory() {
        List<Alarm> alarms = mapper.map(List.of(
                object("smoke", .96),
                object("person", .91),
                object("person", .90),
                object("person", .88),
                object("car", .87),
                object("dog", .82),
                object("backpack", .76)
        ), "A区西门", "CAM-001", "https://example.com/test.jpg");

        assertThat(alarms).extracting(Alarm::getType)
                .containsExactly("烟火异常", "人员聚集", "车辆异常", "动物进入", "物品异常");
        assertThat(alarms.get(0).getLevel()).isEqualTo("高");
        assertThat(alarms).allSatisfy(alarm -> {
            assertThat(alarm.getArea()).isEqualTo("A区西门");
            assertThat(alarm.getStatus()).isEqualTo("待处置");
            assertThat(alarm.getSource()).isEqualTo("AI视觉分析");
            assertThat(alarm.getEventTime()).isNotNull();
        });
    }

    @Test
    void mapsOnePersonToIntrusionAlarm() {
        List<Alarm> alarms = mapper.map(List.of(object("person", .95)), null, null, null);

        assertThat(alarms).hasSize(1);
        assertThat(alarms.get(0).getType()).isEqualTo("人员闯入");
        assertThat(alarms.get(0).getArea()).isEqualTo("未指定区域");
    }

    private AiDetectionObject object(String name, double confidence) {
        return new AiDetectionObject(name, confidence, "test-model");
    }
}
