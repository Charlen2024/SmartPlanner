package com.chao.goal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.chao.common.client")
@EnableAsync
@MapperScan("com.chao.goal.mapper")
public class GoalApplication {
    public static void main(String[] args) {
        SpringApplication.run(GoalApplication.class, args);
    }
}
