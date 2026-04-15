package com.chao.goal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_journals")
public class UserJournal {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long goalId;
    private String content;
    private String mood;
    private LocalDateTime createdAt;
}
