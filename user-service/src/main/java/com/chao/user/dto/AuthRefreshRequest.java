package com.chao.user.dto;

import lombok.Data;

@Data
public class AuthRefreshRequest {
    private String refreshToken;
}

