package com.chao.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chao.schedule.entity.TaskSchedule;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskScheduleMapper extends BaseMapper<TaskSchedule> {
}
