package com.chao.user.controller;

import com.chao.common.dto.Result;
import com.chao.user.dto.AgentChatResponse;
import com.chao.user.service.AgentChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/user/agent")
@RequiredArgsConstructor
public class AgentController {
    private final AgentChatService agentChatService;

    @PostMapping("/chat")
    public Result<AgentChatResponse> chat(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) String question) {
        Long userId = jwt.getClaim("userId");
        String answer = agentChatService.chat(userId, question);
        AgentChatResponse resp = new AgentChatResponse();
        resp.setAnswer(answer);
        return Result.success(resp);
    }

    @PostMapping(value = "/chat/stream", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> chatStream(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) String question) {
        Long userId = jwt.getClaim("userId");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache");
        headers.set("X-Accel-Buffering", "no");

        StreamingResponseBody body = outputStream -> {
            //region debug-point streaming-reactor-stack:user-stream
            String reqId = UUID.randomUUID().toString();
            long startMs = System.currentTimeMillis();
            int[] counter = new int[]{0};
            Map<String, Object> startFields = new LinkedHashMap<>();
            startFields.put("reqId", reqId);
            startFields.put("userId", userId != null ? String.valueOf(userId) : null);
            startFields.put("qLen", question != null ? String.valueOf(question.length()) : "0");
            debugReport("H3", "user_stream_start", startFields);
            //endregion
            try {
                for (String chunk : agentChatService.chatStream(userId, question).toIterable()) {
                    if (chunk == null || chunk.isEmpty()) continue;
                    byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
                    outputStream.write(bytes);
                    outputStream.flush();
                    counter[0] += 1;
                    if (counter[0] <= 5 || counter[0] % 10 == 0) {
                        Map<String, Object> fields = new LinkedHashMap<>();
                        fields.put("reqId", reqId);
                        fields.put("n", String.valueOf(counter[0]));
                        fields.put("bytes", String.valueOf(bytes.length));
                        fields.put("tMs", String.valueOf(System.currentTimeMillis() - startMs));
                        debugReport("H3", "user_stream_chunk", fields);
                    }
                }
                Map<String, Object> endFields = new LinkedHashMap<>();
                endFields.put("reqId", reqId);
                endFields.put("chunks", String.valueOf(counter[0]));
                endFields.put("tMs", String.valueOf(System.currentTimeMillis() - startMs));
                debugReport("H3", "user_stream_complete", endFields);
            } catch (Exception e) {
                Map<String, Object> errFields = new LinkedHashMap<>();
                errFields.put("reqId", reqId);
                errFields.put("chunks", String.valueOf(counter[0]));
                errFields.put("tMs", String.valueOf(System.currentTimeMillis() - startMs));
                errFields.put("errorType", e.getClass().getName());
                errFields.put("errorMsg", String.valueOf(e.getMessage()));
                debugReport("H3", "user_stream_error", errFields);
                throw e;
            }
        };

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .headers(headers)
                .body(body);
    }

    //region debug-point streaming-reactor-stack:debug-report
    private static void debugReport(String hypothesisId, String eventName, Map<String, Object> fields) {
        CompletableFuture.runAsync(() -> {
            String url = System.getenv("DEBUG_SERVER_URL");
            if (url == null || url.isBlank()) {
                url = "http://host.docker.internal:7777/event";
            }
            String sessionId = System.getenv("DEBUG_SESSION_ID");
            if (sessionId == null || sessionId.isBlank()) sessionId = "streaming-reactor-stack";

            StringBuilder sb = new StringBuilder(256);
            sb.append('{');
            appendJson(sb, "ts", String.valueOf(System.currentTimeMillis())).append(',');
            appendJson(sb, "sessionId", sessionId).append(',');
            appendJson(sb, "hypothesisId", hypothesisId).append(',');
            appendJson(sb, "event", eventName).append(',');
            sb.append("\"fields\":").append(mapToJson(fields));
            sb.append('}');

            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(300);
                conn.setReadTimeout(800);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }
                conn.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String mapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            appendJson(sb, e.getKey(), e.getValue() != null ? String.valueOf(e.getValue()) : null);
        }
        sb.append('}');
        return sb.toString();
    }

    private static StringBuilder appendJson(StringBuilder sb, String k, String v) {
        sb.append('\"').append(escapeJson(k)).append("\":");
        if (v == null) {
            sb.append("null");
        } else {
            sb.append('\"').append(escapeJson(v)).append('\"');
        }
        return sb;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(' ');
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
    //endregion
}
