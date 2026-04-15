package com.chao.user.controller;

import com.chao.common.dto.Result;
import com.chao.user.dto.AgentChatResponse;
import com.chao.user.service.AgentChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
