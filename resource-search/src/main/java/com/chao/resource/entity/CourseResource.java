package com.chao.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("course_resources")
public class CourseResource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String topic;
    private String title;
    private String sourceUrl;
    private String platform;
    private String contentSummary;
    private String vectorId;
    private LocalDateTime createdAt;
}
