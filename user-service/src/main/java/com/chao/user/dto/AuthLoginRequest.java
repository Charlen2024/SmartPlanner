package com.chao.user.dto;

import lombok.Data;

@Data
public class AuthLoginRequest {
    private String username;
    private String password;
}

