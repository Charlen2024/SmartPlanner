package com.chao.user.controller;

import com.chao.common.dto.Result;
import com.chao.user.dto.AgentChatResponse;
import com.chao.user.service.AgentChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Slf4j
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

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) String question) {
        Long userId = jwt.getClaim("userId");

        StreamingResponseBody body = outputStream -> {
            agentChatService.chatStream(userId, question)
                    .doOnComplete(() -> {
                        try {
                            outputStream.write("event: done\ndata: \n\n".getBytes());
                            outputStream.flush();
                        } catch (Exception ignored) {}
                    })
                    .doOnError(e -> log.warn("agent stream error: {}", e != null ? e.getMessage() : "unknown"))
                    .doOnNext(chunk -> {
                        try {
                            if (chunk != null && !chunk.isEmpty()) {
                                outputStream.write(("data: " + chunk + "\n\n").getBytes());
                                outputStream.flush();
                            }
                        } catch (Exception ignored) {}
                    })
                    .doOnTerminate(() -> {
                        try { outputStream.close(); } catch (Exception ignored) {}
                    })
                    .blockLast();
        };

        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }
}