package com.chao.common.client;

import com.chao.common.dto.AiPortraitResult;
import com.chao.common.dto.PortraitRecomputeRequest;
import com.chao.common.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "agent-service", contextId = "agent-portrait")
public interface AgentPortraitClient {

    @PostMapping("/api/agent/portrait/recompute")
    Result<AiPortraitResult> recomputePortrait(@RequestBody PortraitRecomputeRequest request);
}
