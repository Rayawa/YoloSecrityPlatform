package top.rayawa.monitor.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 安防设备信息
 * @TableName device
 */
@TableName(value ="device")
@Data
public class Device {
    /**
     * 设备主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 设备唯一编码
     */
    private String code;

    /**
     * 设备名称
     */
    private String name;

    /**
     * 设备类型
     */
    private String type;

    /**
     * 所属区域
     */
    private String area;

    /**
     * 在线状态：在线、离线
     */
    private String status;

}
