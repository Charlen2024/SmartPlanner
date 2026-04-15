package com.chao.goal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chao.goal.entity.Goal;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GoalMapper extends BaseMapper<Goal> {
}
