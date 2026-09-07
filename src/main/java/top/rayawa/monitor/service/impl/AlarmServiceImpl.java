package top.rayawa.monitor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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

import java.util.List;
import java.util.Set;

/**
* @author raychen
* @description 针对表【alarm(安防告警记录)】的数据库操作Service实现
* @createDate 2026-09-07 16:09:25
*/
@Service
public class AlarmServiceImpl extends ServiceImpl<AlarmMapper, Alarm> implements AlarmService {

    private static final Set<String> ALLOWED_STATUSES = Set.of("待处置", "处置中", "已关闭");

    private final AlarmRuleMapper alarmRuleMapper;
    private final AlertWebSocketHandler webSocketHandler;

    public AlarmServiceImpl(
            AlarmRuleMapper alarmRuleMapper,
            AlertWebSocketHandler webSocketHandler
    ) {
        this.alarmRuleMapper = alarmRuleMapper;
        this.webSocketHandler = webSocketHandler;
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
    public Alarm updateStatus(long id, String status) {
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("状态只能是：待处置、处置中、已关闭");
        }
        Alarm alarm = getById(id);
        if (alarm == null) {
            throw new IllegalArgumentException("告警不存在：" + id);
        }
        alarm.setStatus(status);
        baseMapper.updateById(alarm);
        broadcastAfterCommit("alarm-status-changed", alarm);
        return alarm;
    }

    @Override
    @Transactional
    public List<Alarm> createFromDetections(
            List<AiDetectionObject> detections,
            String area,
            String deviceCode,
            String imageUrl
    ) {
        List<Alarm> alarms = alarmRuleMapper.map(detections, area, deviceCode, imageUrl);
        alarms.forEach(alarm -> {
            baseMapper.insert(alarm);
            broadcastAfterCommit("new-alarm", alarm);
        });
        return alarms;
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




