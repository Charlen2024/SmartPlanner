package com.chao.user.controller;

import com.chao.common.dto.Result;
import com.chao.user.dto.AgentChatResponse;
import com.chao.user.service.AgentChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/user/agent")
@RequiredArgsConstructor
public class AgentController {
    private final AgentChatService agentChatService;
    private static final ExecutorService SSE_EXECUTOR = Executors.newCachedThreadPool(
            r -> { Thread t = new Thread(r, "sse-worker"); t.setDaemon(true); return t; }
    );

    @PostMapping("/chat")
    public Result<AgentChatResponse> chat(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) String question) {
        Long userId = jwt.getClaim("userId");
        String answer = agentChatService.chat(userId, question);
        AgentChatResponse resp = new AgentChatResponse();
        resp.setAnswer(answer);
        return Result.success(resp);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) String question,
            HttpServletResponse response) {
        Long userId = jwt.getClaim("userId");
        SseEmitter emitter = new SseEmitter(300_000L);
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SSE_EXECUTOR.execute(() -> {
            try {
                agentChatService.chatStream(userId, question)
                        .doOnComplete(() -> safeComplete(emitter))
                        .doOnError(e -> fallbackComplete(emitter, userId, question, e))
                        .subscribe(
                                chunk -> safeSend(emitter, chunk, response),
                                e -> fallbackComplete(emitter, userId, question, e)
                        );
            } catch (Exception e) {
                log.error("agent stream fatal", e);
                fallbackComplete(emitter, userId, question, e);
            }
        });
        return emitter;
    }

    private void safeSend(SseEmitter emitter, String chunk, HttpServletResponse response) {
        try {
            if (chunk != null && !chunk.isEmpty()) {
                emitter.send(SseEmitter.event().data(chunk));
                try { response.flushBuffer(); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            // client disconnected
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try { emitter.send(SseEmitter.event().name("done").data("")); } catch (IOException ignored) {}
        emitter.complete();
    }

    private void fallbackComplete(SseEmitter emitter, Long userId, String question, Throwable e) {
        log.warn("agent stream error: {}", e != null ? e.getMessage() : "unknown");
        try {
            String fallback = agentChatService.chat(userId, question);
            emitter.send(SseEmitter.event().data(fallback));
        } catch (IOException ignored) {}
        emitter.complete();
    }
}
