package com.chao.punch;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.chao.common.client")
@EnableAsync
@EnableScheduling
@MapperScan("com.chao.punch.mapper")
public class PunchApplication {
    public static void main(String[] args) {
        SpringApplication.run(PunchApplication.class, args);
    }
}
