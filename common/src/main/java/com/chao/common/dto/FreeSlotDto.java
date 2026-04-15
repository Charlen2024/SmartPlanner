package com.chao.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FreeSlotDto {
    private LocalDateTime start;
    private LocalDateTime end;
}

