package top.rayawa.monitor.service;

import org.junit.jupiter.api.Test;
import top.rayawa.monitor.dto.AiDetectionObject;
import top.rayawa.monitor.domain.Alarm;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlarmRuleMapperTest {

    private final AlarmRuleMapper mapper = new AlarmRuleMapper();

    @Test
    void mapsFireTwoPeopleVehicleAnimalItemByPriorityAndLevel() {
        AlarmRuleMapper.MappingResult result = mapper.map(List.of(
                object("smoke", .96),
                object("person", .91),
                object("person", .88),
                object("car", .87),
                object("dog", .82),
                object("suitcase", .76),
                object("traffic light", .70)
        ), "A区西门", "CAM-001", "cam-001/2026-09-08/082301.jpg");

        assertThat(result.alarms()).extracting(Alarm::getType)
                .containsExactly("烟火异常", "人员聚集", "车辆异常", "动物进入", "物品异常");
        assertThat(result.alarms()).extracting(Alarm::getLevel)
                .containsExactly("高", "高", "中", "中", "中");
        assertThat(result.skipped()).containsExactly("traffic light");
        assertThat(result.alarms()).allSatisfy(alarm -> {
            assertThat(alarm.getArea()).isEqualTo("A区西门");
            assertThat(alarm.getStatus()).isEqualTo("待处置");
            assertThat(alarm.getSource()).isEqualTo("AI视觉分析");
            assertThat(alarm.getEventTime()).isNotNull();
        });
    }

    @Test
    void mapsSinglePersonToMediumIntrusionAlarm() {
        AlarmRuleMapper.MappingResult result = mapper.map(List.of(object("person", .95)), null, null, null);

        assertThat(result.alarms()).hasSize(1);
        assertThat(result.alarms().get(0).getType()).isEqualTo("人员闯入");
        assertThat(result.alarms().get(0).getLevel()).isEqualTo("中");
        assertThat(result.alarms().get(0).getArea()).isEqualTo("未指定区域");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void skipsOnlyUnknownTargetsWhenNoKnownObject() {
        AlarmRuleMapper.MappingResult result = mapper.map(List.of(
                object("traffic light", .70),
                object("chair", .65)
        ), "A区西门", null, null);

        assertThat(result.alarms()).isEmpty();
        assertThat(result.skipped()).containsExactly("traffic light", "chair");
    }

    @Test
    void boundaryPersonCountOneVsTwo() {
        // 边界值：1 人 → 人员闯入(中)；2 人 → 人员聚集(高)
        AlarmRuleMapper.MappingResult single = mapper.map(List.of(object("person", .95)), null, null, null);
        AlarmRuleMapper.MappingResult crowd = mapper.map(
                List.of(object("person", .95), object("person", .93)), null, null, null);

        assertThat(single.alarms()).extracting(Alarm::getType).containsExactly("人员闯入");
        assertThat(single.alarms()).extracting(Alarm::getLevel).containsExactly("中");
        assertThat(crowd.alarms()).extracting(Alarm::getType).containsExactly("人员聚集");
        assertThat(crowd.alarms()).extracting(Alarm::getLevel).containsExactly("高");
    }

    @Test
    void mapsEmptyListToEmptyResult() {
        AlarmRuleMapper.MappingResult result = mapper.map(List.of(), "A区", null, null);

        assertThat(result.alarms()).isEmpty();
        assertThat(result.skipped()).isEmpty();
    }

    private AiDetectionObject object(String name, double confidence) {
        return new AiDetectionObject(name, confidence, "test-model");
    }
}
