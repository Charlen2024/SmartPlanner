package com.chao.common.client;

import com.chao.common.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@FeignClient(name = "user-service", contextId = "user-internal")
public interface UserInternalClient {

    @GetMapping("/api/user/internal/users/ids")
    Result<List<Long>> getAllUserIds();

    @GetMapping("/api/user/notifications/active-user-ids/internal")
    List<Long> getActiveUserIds();
}
