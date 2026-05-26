package com.chao.common.ai;

import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@AutoConfiguration
public class OpenAiCompatAutoConfiguration {
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public RestClientCustomizer smartPlannerAiRestClientCustomizer(
            org.springframework.core.env.Environment environment) {
        return restClientBuilder -> {
            Integer readTimeoutSeconds = environment.getProperty("spring.ai.dashscope.read-timeout", Integer.class);
            if (readTimeoutSeconds == null || readTimeoutSeconds <= 0) {
                readTimeoutSeconds = 180;
            }
            restClientBuilder.requestFactory(ClientHttpRequestFactories.get(
                    ClientHttpRequestFactorySettings.DEFAULTS.withReadTimeout(Duration.ofSeconds(readTimeoutSeconds))));
        };
    }

    @Bean
    public OpenAiCompatClient openAiCompatClient(
            org.springframework.beans.factory.ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            throw new IllegalStateException("ChatClient.Builder not found. Please ensure spring-ai-alibaba-starter-dashscope is on the classpath and spring.ai.dashscope.api-key is configured.");
        }
        return new OpenAiCompatClient(builder.build());
    }
}
