package top.rayawa.monitor.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import top.rayawa.monitor.domain.Device;
import top.rayawa.monitor.service.DeviceService;
import top.rayawa.monitor.mapper.DeviceMapper;
import org.springframework.stereotype.Service;

/**
* @author raychen
* @description 针对表【device(安防设备信息)】的数据库操作Service实现
* @createDate 2026-09-07 16:09:25
*/
@Service
public class DeviceServiceImpl extends ServiceImpl<DeviceMapper, Device>
    implements DeviceService{

}




