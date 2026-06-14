package com.chao.user.controller;

import com.chao.common.client.PunchClient;
import com.chao.common.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class PunchController {

    private final PunchClient punchClient;
    private final UserControllerSupport support;

    @PostMapping("/punch/submit")
    public Result<String> submitPunch(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam Long taskId,
            @RequestParam Integer type,
            @RequestParam(required = false) Integer durationSeconds,
            @RequestParam(required = false) Long startedAtMs,
            @RequestParam(required = false) Long endedAtMs,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String taskTitle,
            @RequestParam(required = false) MultipartFile evidence) {
        return punchClient.submitPunch(support.resolveUserId(jwt, headerUserId, userId),
                taskId, type, durationSeconds, startedAtMs, endedAtMs, location, taskTitle, evidence);
    }

    @GetMapping("/punch/records")
    public Result<List<PunchRecordDto>> listPunchRecords(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return punchClient.listRecords(support.resolveUserId(jwt, headerUserId, userId), null, null, null);
    }

    @DeleteMapping("/punch/records/{recordId}")
    public Result<String> deletePunchRecord(@PathVariable Long recordId) {
        return punchClient.deleteRecord(recordId);
    }

    @GetMapping("/punch/streak")
    public Result<Long> getStreak(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return punchClient.getStreak(support.resolveUserId(jwt, headerUserId, userId));
    }

    @GetMapping("/punch/habits")
    public Result<UserHabitDto> getHabits(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return punchClient.getHabits(support.resolveUserId(jwt, headerUserId, userId));
    }

    @PutMapping("/punch/habits")
    public Result<UserHabitDto> updateHabits(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer morningPersonScore,
            @RequestParam(required = false) Integer focusDurationAvg,
            @RequestParam(required = false) Float procrastinationIndex) {
        return punchClient.updateHabits(support.resolveUserId(jwt, headerUserId, userId),
                morningPersonScore, focusDurationAvg, procrastinationIndex);
    }
}
