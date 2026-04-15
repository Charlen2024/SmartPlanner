package com.chao.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

public class OpenAiBaseUrlSanitizer implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String key = "spring.ai.openai.base-url";
        String raw = environment.getProperty(key);
        if (raw == null) {
            return;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return;
        }
        String sanitized = stripTrailingV1(v);
        if (sanitized.equals(v)) {
            return;
        }
        Map<String, Object> map = new HashMap<>();
        map.put(key, sanitized);
        environment.getPropertySources().addFirst(new MapPropertySource("openAiBaseUrlSanitized", map));
    }

    private String stripTrailingV1(String s) {
        String v = s;
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        if (v.endsWith("/v1")) {
            v = v.substring(0, v.length() - 3);
        }
        return v;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

