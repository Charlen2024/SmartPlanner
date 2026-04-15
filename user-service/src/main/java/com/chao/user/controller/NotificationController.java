package com.chao.user.controller;

import com.chao.common.dto.NotificationMessage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/user/notifications")
public class NotificationController {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long userId) {
        Long uid = jwt != null ? jwt.getClaim("userId") : userId;
        if (uid == null) {
            throw new IllegalArgumentException("未授权");
        }

        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.computeIfAbsent(uid, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(uid, emitter));
        emitter.onTimeout(() -> removeEmitter(uid, emitter));
        emitter.onError(e -> removeEmitter(uid, emitter));

        try {
            emitter.send(SseEmitter.event().name("ping").data("connected"));
        } catch (IOException e) {
            removeEmitter(uid, emitter);
        }

        return emitter;
    }

    private void removeEmitter(Long userId, SseEmitter emitter) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            userEmitters.remove(emitter);
            if (userEmitters.isEmpty()) {
                emitters.remove(userId);
            }
        }
    }

    public void pushNotification(NotificationMessage message) {
        Long userId = message.getUserId();
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            for (SseEmitter emitter : userEmitters) {
                try {
                    emitter.send(SseEmitter.event().name(message.getType()).data(message));
                } catch (IOException e) {
                    removeEmitter(userId, emitter);
                }
            }
        }
    }

}
