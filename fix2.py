import pathlib
base = pathlib.Path(r'C:\Users\刘超\Documents\SmartPlanner')
f = base / 'user-service/src/main/java/com/chao/user/controller/AgentController.java'
java = f.read_text(encoding='utf-8')

# Replace entire file content with simplified version
new_content = '''package com.chao.user.controller;

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
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

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
    public void chatStream(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) String question,
            HttpServletResponse response) throws IOException {
        Long userId = jwt.getClaim("userId");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        ServletOutputStream out = response.getOutputStream();

        agentChatService.chatStream(userId, question)
                .doOnComplete(() -> {
                    try { out.write("event: done\\ndata: \\n\\n".getBytes()); out.flush(); } catch (IOException ignored) {}
                })
                .doOnError(e -> log.warn("agent stream error: {}", e != null ? e.getMessage() : "unknown"))
                .subscribe(
                        chunk -> {
                            try {
                                if (chunk != null && !chunk.isEmpty()) {
                                    out.write(("data: " + chunk + "\\n\\n").getBytes());
                                    out.flush();
                                }
                            } catch (IOException ignored) {}
                        },
                        e -> {
                            log.warn("agent stream error: {}", e != null ? e.getMessage() : "unknown");
                            try {
                                String fallback = agentChatService.chat(userId, question);
                                out.write(("data: " + fallback + "\\n\\nevent: done\\ndata: \\n\\n").getBytes());
                                out.flush();
                            } catch (IOException ignored) {}
                        }
                );
    }
}
'''

# Fix the escaped newlines in the Java string literals
new_content = new_content.replace('\\ndata: \\n\\n', '\ndata: \n\n')
new_content = new_content.replace('\\n\\n").getBytes', '\n\n").getBytes')

f.write_text(new_content, encoding='utf-8')
print('AgentController rewritten')
