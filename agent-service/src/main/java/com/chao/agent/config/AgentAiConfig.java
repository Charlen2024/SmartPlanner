package com.chao.agent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestTemplate;
import redis.clients.jedis.JedisPooled;

@Configuration
public class AgentAiConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentAiConfig.class);

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(Environment environment) {
        String host = resolveRedisHost(environment);
        int port = resolveRedisPort(environment);
        String password = resolveRedisPassword(environment);

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
        String host = resolveRedisHost(environment);
        int port = resolveRedisPort(environment);
        String password = resolveRedisPassword(environment);
        if (password != null && !password.isBlank()) {
            return new JedisPooled(host, port, null, password);
        }
        return new JedisPooled(host, port);
    }

    private static String resolveRedisHost(Environment env) {
        String host = env.getProperty("spring.redis.host");
        if (host != null && !host.isBlank()) return host;
        host = env.getProperty("spring.data.redis.host");
        if (host != null && !host.isBlank()) return host;
        host = env.getProperty("SPRING_REDIS_HOST");
        if (host != null && !host.isBlank()) return host;
        return "localhost";
    }

    private static int resolveRedisPort(Environment env) {
        Integer port = env.getProperty("spring.redis.port", Integer.class);
        if (port != null && port > 0) return port;
        port = env.getProperty("spring.data.redis.port", Integer.class);
        if (port != null && port > 0) return port;
        port = env.getProperty("SPRING_REDIS_PORT", Integer.class);
        if (port != null && port > 0) return port;
        return 6379;
    }

    @Nullable
    private static String resolveRedisPassword(Environment env) {
        String password = env.getProperty("spring.redis.password");
        if (password != null && !password.isBlank()) return password;
        return env.getProperty("spring.data.redis.password");
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
            log.warn("DashScope API Key is not configured. AI features (chat/embedding) will fail at runtime. Set AI_DASHSCOPE_API_KEY or spring.ai.dashscope.api-key.");
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
