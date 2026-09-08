package top.rayawa.monitor.service;

import top.rayawa.monitor.domain.Alarm;
import com.baomidou.mybatisplus.extension.service.IService;
import top.rayawa.monitor.dto.AiDetectionObject;

import java.util.List;

/**
* @author raychen
* @description 针对表【alarm(安防告警记录)】的数据库操作Service
* @createDate 2026-09-07 16:09:25
*/
public interface AlarmService extends IService<Alarm> {

    List<Alarm> list(String status, String source);

    /**
     * 开始处置：待处置 -> 处置中（讲义接口 POST /api/alarms/{id}/process）。
     */
    Alarm process(long id);

    /**
     * 处理完成：待处置/处置中 -> 已关闭（讲义接口 POST /api/alarms/{id}/handle）。
     */
    Alarm handle(long id);

    /**
     * 按告警规则把识别结果映射为告警并入库。
     *
     * @param imageData 标注图 base64（可空），保存到 uploads/ 后写入告警 imagePath
     * @return 映射结果（含被跳过的目标识别异常类名）
     */
    AlarmRuleMapper.MappingResult createFromDetections(
            List<AiDetectionObject> detections,
            String area,
            String deviceCode,
            String imageUrl,
            String imageData
    );

}
