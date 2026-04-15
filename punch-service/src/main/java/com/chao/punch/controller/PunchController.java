package com.chao.punch.controller;

import com.chao.common.util.DateUtils;
import com.chao.common.dto.Result;
import com.chao.punch.entity.PunchRecord;
import com.chao.punch.entity.UserHabit;
import com.chao.punch.service.PunchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/punch")
@RequiredArgsConstructor
public class PunchController {
    private final PunchService punchService;

    /**
     * 提交打卡
     * 支持 GPS 经纬度、图片上传
     */
    @PostMapping("/submit")
    public Result<String> submitPunch(
            @RequestParam Long userId,
            @RequestParam Long taskId,
            @RequestParam Integer type,
            @RequestParam(required = false) Integer durationSeconds,
            @RequestParam(required = false) Long startedAtMs,
            @RequestParam(required = false) Long endedAtMs,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) MultipartFile evidence) {
        PunchRecord record = punchService.submitPunch(userId, taskId, type, durationSeconds, startedAtMs, endedAtMs, location, evidence);
        return Result.success("打卡已提交，recordId=" + record.getId());
    }

    @GetMapping("/records")
    public Result<List<PunchRecord>> listRecords(
            @RequestParam Long userId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return Result.success(punchService.listRecords(userId, taskId, DateUtils.parseLocalDateTime(from), DateUtils.parseLocalDateTime(to)));
    }

    @DeleteMapping("/records/{recordId}")
    public Result<String> deleteRecord(@PathVariable Long recordId) {
        punchService.deleteRecord(recordId);
        return Result.success("删除成功");
    }

    @GetMapping("/streak")
    public Result<Long> getStreak(@RequestParam Long userId) {
        return Result.success(punchService.getStreak(userId));
    }

    @GetMapping("/habits")
    public Result<UserHabit> getHabits(@RequestParam Long userId) {
        return Result.success(punchService.getOrCreateHabit(userId));
    }

    @PutMapping("/habits")
    public Result<UserHabit> updateHabits(
            @RequestParam Long userId,
            @RequestParam(required = false) Integer morningPersonScore,
            @RequestParam(required = false) Integer focusDurationAvg,
            @RequestParam(required = false) Float procrastinationIndex) {
        return Result.success(punchService.updateHabit(userId, morningPersonScore, focusDurationAvg, procrastinationIndex));
    }
}
