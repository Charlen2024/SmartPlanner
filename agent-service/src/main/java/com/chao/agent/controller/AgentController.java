package com.chao.agent.controller;

import com.chao.common.dto.AiPortraitResult;
import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.PortraitRecomputeRequest;
import com.chao.common.dto.Result;
import com.chao.common.dto.ScheduleAdviceRequest;
import com.chao.common.dto.ScheduleAdviceResponse;
import com.chao.agent.dto.AgentChatResponse;
import com.chao.agent.service.AgentChatService;
import com.chao.agent.service.TaskAdviceAiService;
import com.chao.agent.service.UserPortraitAiService;
import com.chao.agent.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {
    private final AgentChatService agentChatService;
    private final UserPortraitAiService userPortraitAiService;
    private final TaskAdviceAiService taskAdviceAiService;

    private static String extractMessage(Map<String, Object> body) {
        if (body == null) return null;
        Object msg = body.get("message");
        return msg instanceof String s ? s : null;
    }

    @PostMapping("/chat")
    public Result<AgentChatResponse> chat(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) Map<String, Object> body) {
        Long userId = JwtUtils.getUserId(jwt);
        String question = extractMessage(body);
        String answer = agentChatService.chat(userId, question);
        AgentChatResponse resp = new AgentChatResponse();
        resp.setAnswer(answer);
        return Result.success(resp);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) Map<String, Object> body) {
        Long userId = JwtUtils.getUserId(jwt);
        String question = extractMessage(body);

        StreamingResponseBody streamBody = outputStream -> {
            java.util.concurrent.atomic.AtomicReference<String> lastSent = new java.util.concurrent.atomic.AtomicReference<>("");
            agentChatService.chatStream(userId, question)
                    .doOnComplete(() -> {
                        try {
                            outputStream.write("event: done\ndata: \n\n".getBytes());
                            outputStream.flush();
                        } catch (Exception e) {
                            log.debug("stream done write failed: {}", e.getMessage());
                        }
                    })
                    .doOnError(e -> log.warn("agent stream error: {}", e != null ? e.getMessage() : "unknown"))
                    .doOnNext(chunk -> {
                        try {
                            if (chunk != null && !chunk.isEmpty()) {
                                String prev = lastSent.getAndSet(chunk);
                                if (chunk.equals(prev)) {
                                    log.debug("skipping duplicate chunk: {}", chunk.length());
                                    return;
                                }
                                outputStream.write(("data: " + chunk + "\n\n").getBytes());
                                outputStream.flush();
                            }
                        } catch (Exception e) {
                            log.debug("stream write chunk failed: {}", e.getMessage());
                        }
                    })
                    .doOnTerminate(() -> {
                        try { outputStream.close(); } catch (Exception e) {
                            log.debug("stream close failed: {}", e.getMessage());
                        }
                    })
                    .blockLast();
        };

        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(streamBody);
    }

    @PostMapping("/warmup")
    public Result<String> warmup(@AuthenticationPrincipal Jwt jwt) {
        Long userId = JwtUtils.getUserId(jwt);
        agentChatService.warmup(userId);
        return Result.success("ok");
    }

    @PostMapping("/portrait/recompute")
    public Result<AiPortraitResult> recomputePortrait(@RequestBody PortraitRecomputeRequest request) {
        AiPortraitResult result = userPortraitAiService.analyze(request);
        return Result.success(result);
    }

    @PostMapping("/tasks/advice")
    public Result<Map<Long, String>> adviseTasks(@RequestBody List<GoalTaskDto> tasks) {
        return Result.success(taskAdviceAiService.advise(tasks));
    }

    @PostMapping("/schedule/advice")
    public Result<ScheduleAdviceResponse> adviseSchedules(@RequestBody ScheduleAdviceRequest request) {
        return Result.success(taskAdviceAiService.adviseSchedules(request.getItems(), request.getMoodHint()));
    }
}
