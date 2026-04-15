package com.chao.common.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class OpenAiCompatClient {
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    public String complete(String prompt) {
        String apiKey = firstNonBlank(
                environment.getProperty("spring.ai.openai.api-key"),
                environment.getProperty("OPENAI_API_KEY")
        );
        String baseUrl = firstNonBlank(
                environment.getProperty("spring.ai.openai.base-url"),
                environment.getProperty("BASE_URL"),
                "https://api.openai.com"
        );
        String model = firstNonBlank(
                environment.getProperty("spring.ai.openai.chat.options.model"),
                environment.getProperty("MODEL"),
                "gpt-4o-mini"
        );

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY not set");
        }
        String url = buildChatCompletionsUrl(baseUrl);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("temperature", 0.2);
        body.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));

        byte[] bytes = restClientBuilder.build()
                .post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(byte[].class);

        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("empty llm response");
        }

        try {
            JsonNode root = objectMapper.readTree(bytes);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            String text = content.isTextual() ? content.asText() : null;
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("llm response missing content");
            }
            return text;
        } catch (Exception e) {
            throw new IllegalStateException("llm response parse failed", e);
        }
    }

    private String buildChatCompletionsUrl(String baseUrl) {
        String b = baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        String apiPrefix = b.endsWith("/v1") ? b : (b + "/v1");
        return apiPrefix + "/chat/completions";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }
}
