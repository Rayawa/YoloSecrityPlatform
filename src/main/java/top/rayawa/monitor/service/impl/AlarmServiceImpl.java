package top.rayawa.monitor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.rayawa.monitor.domain.Alarm;
import top.rayawa.monitor.dto.AiDetectionObject;
import top.rayawa.monitor.mapper.AlarmMapper;
import top.rayawa.monitor.service.AlarmRuleMapper;
import top.rayawa.monitor.service.AlarmService;
import top.rayawa.monitor.websocket.AlertWebSocketHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
* @author raychen
* @description 针对表【alarm(安防告警记录)】的数据库操作Service实现
* @createDate 2026-09-07 16:09:25
*/
@Service
public class AlarmServiceImpl extends ServiceImpl<AlarmMapper, Alarm> implements AlarmService {

    private final AlarmRuleMapper alarmRuleMapper;
    private final AlertWebSocketHandler webSocketHandler;
    private final Path imageDir;

    public AlarmServiceImpl(
            AlarmRuleMapper alarmRuleMapper,
            AlertWebSocketHandler webSocketHandler,
            @Value("${alarm.image-dir}") String imageDir
    ) {
        this.alarmRuleMapper = alarmRuleMapper;
        this.webSocketHandler = webSocketHandler;
        this.imageDir = Path.of(imageDir).toAbsolutePath();
        try {
            Files.createDirectories(this.imageDir);
        } catch (Exception ignored) {
            // 目录创建失败时保存图片会自动跳过，不影响告警入库
        }
    }

    @Override
    public List<Alarm> list(String status, String source) {
        QueryWrapper<Alarm> query = new QueryWrapper<Alarm>().orderByDesc("event_time");
        if (status != null && !status.isBlank()) {
            query.eq("status", status.trim());
        }
        if (source != null && !source.isBlank()) {
            query.eq("source", source.trim());
        }
        return baseMapper.selectList(query);
    }

    @Override
    @Transactional
    public Alarm process(long id) {
        Alarm alarm = requireExists(id);
        if (!"待处置".equals(alarm.getStatus())) {
            throw new IllegalArgumentException("仅待处置的告警可以开始处置，当前状态：" + alarm.getStatus());
        }
        alarm.setStatus("处置中");
        baseMapper.updateById(alarm);
        broadcastAfterCommit("alarm-status-changed", alarm);
        return alarm;
    }

    @Override
    @Transactional
    public Alarm handle(long id) {
        Alarm alarm = requireExists(id);
        if ("已关闭".equals(alarm.getStatus())) {
            throw new IllegalArgumentException("该告警已关闭，无需重复处理");
        }
        alarm.setStatus("已关闭");
        baseMapper.updateById(alarm);
        broadcastAfterCommit("alarm-status-changed", alarm);
        return alarm;
    }

    @Override
    @Transactional
    public AlarmRuleMapper.MappingResult createFromDetections(
            List<AiDetectionObject> detections,
            String area,
            String deviceCode,
            String imageUrl,
            String imageData
    ) {
        // 标注图先落盘（一次识别一张图，本批告警共用同一路径）；保存失败仅无图，不影响入库
        String imagePath = saveAnnotatedImage(imageData);
        AlarmRuleMapper.MappingResult result = alarmRuleMapper.map(detections, area, deviceCode, imageUrl);
        if (imagePath != null) {
            result.alarms().forEach(alarm -> alarm.setImagePath(imagePath));
        }
        // 被跳过的目标识别异常对象不入库、不推送，天然满足"不入库不推送"
        result.alarms().forEach(alarm -> {
            baseMapper.insert(alarm);
            broadcastAfterCommit("new-alarm", alarm);
        });
        return result;
    }

    private String saveAnnotatedImage(String imageData) {
        if (imageData == null || imageData.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(imageData);
            String filename = "alarm_" + System.currentTimeMillis() + ".jpg";
            Files.write(imageDir.resolve(filename), bytes);
            return "/uploads/" + filename;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Alarm requireExists(long id) {
        Alarm alarm = getById(id);
        if (alarm == null) {
            throw new IllegalArgumentException("告警不存在：" + id);
        }
        return alarm;
    }

    private void broadcastAfterCommit(String event, Alarm alarm) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            webSocketHandler.broadcast(event, alarm);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                webSocketHandler.broadcast(event, alarm);
            }
        });
    }

}
