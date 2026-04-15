package com.chao.user.dto;

import lombok.Data;

@Data
public class AuthTokenResponse {
    private String tokenType;
    private String accessToken;
    private String refreshToken;
    private Long expiresInSeconds;
}

