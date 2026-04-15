package com.chao.common.client;

import com.chao.common.dto.Result;
import com.chao.common.dto.PunchRecordDto;
import com.chao.common.dto.UserHabitDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@FeignClient(name = "punch-service")
public interface PunchClient {
    @PostMapping(value = "/api/punch/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Result<String> submitPunch(
            @RequestParam("userId") Long userId,
            @RequestParam("taskId") Long taskId,
            @RequestParam("type") Integer type,
            @RequestParam(value = "durationSeconds", required = false) Integer durationSeconds,
            @RequestParam(value = "startedAtMs", required = false) Long startedAtMs,
            @RequestParam(value = "endedAtMs", required = false) Long endedAtMs,
            @RequestParam(value = "location", required = false) String location,
            @RequestPart(value = "evidence", required = false) MultipartFile evidence);

    @GetMapping("/api/punch/records")
    Result<List<PunchRecordDto>> listRecords(
            @RequestParam("userId") Long userId,
            @RequestParam(value = "taskId", required = false) Long taskId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to);

    @DeleteMapping("/api/punch/records/{recordId}")
    Result<String> deleteRecord(@PathVariable("recordId") Long recordId);

    @GetMapping("/api/punch/streak")
    Result<Long> getStreak(@RequestParam("userId") Long userId);

    @GetMapping("/api/punch/habits")
    Result<UserHabitDto> getHabits(@RequestParam("userId") Long userId);

    @PutMapping("/api/punch/habits")
    Result<UserHabitDto> updateHabits(
            @RequestParam("userId") Long userId,
            @RequestParam(value = "morningPersonScore", required = false) Integer morningPersonScore,
            @RequestParam(value = "focusDurationAvg", required = false) Integer focusDurationAvg,
            @RequestParam(value = "procrastinationIndex", required = false) Float procrastinationIndex);
}
