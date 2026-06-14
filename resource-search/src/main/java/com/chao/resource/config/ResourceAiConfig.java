package com.chao.resource.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class ResourceAiConfig {

    private static final Logger log = LoggerFactory.getLogger(ResourceAiConfig.class);

    @Bean
    @ConditionalOnClass(DashScopeApi.class)
    @ConditionalOnMissingBean(DashScopeApi.class)
    public DashScopeApi dashScopeApi(Environment environment) {
        String apiKey = environment.getProperty("spring.ai.dashscope.api-key");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = environment.getProperty("AI_DASHSCOPE_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "";
            log.warn("DashScope API Key is not configured. Resource embedding will be unavailable.");
        }
        return DashScopeApi.builder().apiKey(apiKey).build();
    }

    @Bean
    @ConditionalOnClass(DashScopeEmbeddingModel.class)
    @ConditionalOnBean(DashScopeApi.class)
    public EmbeddingModel embeddingModel(Environment environment, DashScopeApi dashScopeApi) {
        String model = environment.getProperty("spring.ai.dashscope.embedding.options.model");
        if (model == null || model.isBlank()) {
            model = "text-embedding-v2";
        }
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder().model(model).build();
        return new DashScopeEmbeddingModel(dashScopeApi, org.springframework.ai.document.MetadataMode.NONE, options);
    }
}
