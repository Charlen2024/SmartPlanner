package com.chao.common.ai;

import org.springframework.ai.chat.client.ChatClient;

public class OpenAiCompatClient {
    private final ChatClient chatClient;

    public OpenAiCompatClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String complete(String prompt) {
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient not configured");
        }
        return chatClient.prompt(prompt).call().content();
    }

    public String complete(String systemPrompt, String userPrompt) {
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient not configured");
        }
        String sys = systemPrompt == null ? "" : systemPrompt;
        String usr = userPrompt == null ? "" : userPrompt;
        return chatClient
                .prompt()
                .system(sys)
                .user(usr)
                .call()
                .content();
    }
}
