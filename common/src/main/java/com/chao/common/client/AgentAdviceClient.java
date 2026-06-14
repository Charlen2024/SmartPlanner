package com.chao.common.client;

import com.chao.common.dto.GoalTaskDto;
import com.chao.common.dto.Result;
import com.chao.common.dto.ScheduleAdviceRequest;
import com.chao.common.dto.ScheduleAdviceResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(name = "agent-service", contextId = "agent-advice")
public interface AgentAdviceClient {

    @PostMapping("/api/agent/tasks/advice")
    Result<Map<Long, String>> adviseTasks(@RequestBody List<GoalTaskDto> tasks);

    @PostMapping("/api/agent/schedule/advice")
    Result<ScheduleAdviceResponse> adviseSchedules(@RequestBody ScheduleAdviceRequest request);
}
