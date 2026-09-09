package top.rayawa.monitor.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

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
     * 标注图/视频帧拼接图路径（如 /uploads/xxx.jpg，可空）
     */
    private String imagePath;

    /**
     * 事件发生时间
     */
    private Date eventTime;

}
