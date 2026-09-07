package top.rayawa.monitor.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/**
 * 安防告警记录
 * @TableName alarm
 */
@TableName(value ="alarm")
@Data
public class Alarm {
    /**
     * 告警主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 告警类型
     */
    private String type;

    /**
     * 发生区域
     */
    private String area;

    /**
     * 告警等级：高、中、低
     */
    private String level;

    /**
     * 处置状态：待处置、处置中、已关闭
     */
    private String status;

    /**
     * 告警来源
     */
    private String source;

    /**
     * 识别结果或告警详情
     */
    private String detail;

    /**
     * 事件发生时间
     */
    private Date eventTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Date getEventTime() {
        return eventTime;
    }

    public void setEventTime(Date eventTime) {
        this.eventTime = eventTime;
    }
}
