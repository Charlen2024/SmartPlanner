package com.chao.common.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

@AutoConfiguration
public class OpenAiCompatAutoConfiguration {
    @Bean
    public OpenAiCompatClient openAiCompatClient(ObjectMapper objectMapper, Environment environment) {
        return new OpenAiCompatClient(RestClient.builder(), objectMapper, environment);
    }
}
