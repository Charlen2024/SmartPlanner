package com.chao.user.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import redis.clients.jedis.JedisPooled;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(Environment environment) {
        String host = environment.getProperty("spring.redis.host");
        if (host == null || host.isBlank()) {
            host = environment.getProperty("spring.data.redis.host");
        }
        if (host == null || host.isBlank()) {
            host = environment.getProperty("SPRING_REDIS_HOST");
        }
        if (host == null || host.isBlank()) {
            host = "localhost";
        }

        Integer port = environment.getProperty("spring.redis.port", Integer.class);
        if (port == null) {
            port = environment.getProperty("spring.data.redis.port", Integer.class);
        }
        if (port == null) {
            port = environment.getProperty("SPRING_REDIS_PORT", Integer.class);
        }
        if (port == null || port <= 0) {
            port = 6379;
        }

        String password = environment.getProperty("spring.redis.password");
        if (password == null || password.isBlank()) {
            password = environment.getProperty("spring.data.redis.password");
        }

        Config config = new Config();
        String address = "redis://" + host + ":" + port;
        var single = config.useSingleServer().setAddress(address);
        if (password != null && !password.isBlank()) {
            single.setPassword(password);
        }
        return Redisson.create(config);
    }

    @Bean
    public JedisPooled jedisPooled(Environment environment) {
        String host = environment.getProperty("spring.redis.host");
        if (host == null || host.isBlank()) {
            host = environment.getProperty("spring.data.redis.host");
        }
        if (host == null || host.isBlank()) {
            host = environment.getProperty("SPRING_REDIS_HOST");
        }
        if (host == null || host.isBlank()) {
            host = "localhost";
        }

        Integer port = environment.getProperty("spring.redis.port", Integer.class);
        if (port == null) {
            port = environment.getProperty("spring.data.redis.port", Integer.class);
        }
        if (port == null) {
            port = environment.getProperty("SPRING_REDIS_PORT", Integer.class);
        }
        if (port == null || port <= 0) {
            port = 6379;
        }

        return new JedisPooled(host, port);
    }

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
        }
        return DashScopeApi.builder().apiKey(apiKey).build();
    }

    @Bean
    @ConditionalOnClass(DashScopeEmbeddingModel.class)
    @ConditionalOnBean(DashScopeApi.class)
    public EmbeddingModel embeddingModel(Environment environment, DashScopeApi dashScopeApi) {
        String model = environment.getProperty("spring.ai.dashscope.embedding.options.model");
        if (model == null || model.isBlank()) {
            model = environment.getProperty("DASHSCOPE_EMBEDDING_MODEL");
        }
        if (model == null || model.isBlank()) {
            model = "text-embedding-v2";
        }
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder().model(model).build();
        return new DashScopeEmbeddingModel(dashScopeApi, org.springframework.ai.document.MetadataMode.NONE, options);
    }

    @Bean
    @ConditionalOnClass(RedisVectorStore.class)
    @ConditionalOnBean(EmbeddingModel.class)
    public VectorStore vectorStore(JedisPooled jedisPooled, EmbeddingModel embeddingModel) {
        return RedisVectorStore.builder(jedisPooled, embeddingModel)
                .indexName("smartplanner-rag")
                .prefix("sp:emb:")
                .initializeSchema(true)
                .build();
    }
}
