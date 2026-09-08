package top.rayawa.monitor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * 把 /uploads/** 映射到磁盘目录（告警标注图/视频帧落盘文件），
 * 目录由 alarm.image-dir 配置（jar 运行时 classpath 不可写，必须落到磁盘目录）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String imageDir;

    public WebConfig(@Value("${alarm.image-dir}") String imageDir) {
        this.imageDir = imageDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(imageDir).toAbsolutePath() + "/";
        registry.addResourceHandler("/uploads/**").addResourceLocations("file:" + location);
    }
}
