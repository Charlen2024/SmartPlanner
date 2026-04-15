package com.chao.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScheduleImportResultDto {
    private String fileName;
    private String format;
    private Integer total;
    private Integer inserted;
    private Integer skipped;
    private List<String> warnings;
}

