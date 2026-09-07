package top.rayawa.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan("top.rayawa.monitor.mapper")
public class SecurityMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecurityMonitorApplication.class, args);
    }
}
