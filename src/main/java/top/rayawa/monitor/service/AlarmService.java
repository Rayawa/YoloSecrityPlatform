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

    Alarm updateStatus(long id, String status);

    List<Alarm> createFromDetections(
            List<AiDetectionObject> detections,
            String area,
            String deviceCode,
            String imageUrl
    );

}
