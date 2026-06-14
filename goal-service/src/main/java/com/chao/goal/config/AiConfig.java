package com.chao.goal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "smartplanner.ai")
public class AiConfig {
    private int decomposeTimeoutSeconds = 90;
}
