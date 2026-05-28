package com.chao.user.dto;

import lombok.Data;

import java.util.List;

@Data
public class AuthMeResponse {
    private Long userId;
    private String username;
    private List<String> roles;
    private Boolean scheduleImported;
    private java.time.LocalDate firstWeekMonday;
}
