package com.chao.resource.controller;

import com.chao.common.dto.Result;
import com.chao.common.client.ResourceClient;
import com.chao.common.dto.ResourceAdviceJobStartRequest;
import com.chao.common.dto.ResourceAdviceJobStartResponse;
import com.chao.common.dto.ResourceAdviceJobStatusResponse;
import com.chao.resource.entity.CourseResource;
import com.chao.resource.service.ResourceAdviceJobService;
import com.chao.resource.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/resources")
@RequiredArgsConstructor
public class ResourceController {
    private final ResourceService resourceService;
    private final ResourceAdviceJobService resourceAdviceJobService;

    @PostMapping
    public Result<CourseResource> create(
            @RequestParam String topic,
            @RequestParam String title,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String url,
            @RequestParam(required = false) String summary) {
        return Result.success(resourceService.createResource(topic, title, platform, url, summary));
    }

    @GetMapping
    public Result<List<CourseResource>> list(@RequestParam(required = false) String topic) {
        return Result.success(resourceService.listResources(topic));
    }

    @GetMapping("/{id}")
    public Result<CourseResource> get(@PathVariable Long id) {
        return Result.success(resourceService.getResource(id));
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        resourceService.deleteResource(id);
        return Result.success("删除成功");
    }

    /**
     * 搜索在线课程
     * 语义检索 + 向量检索
     */
    @GetMapping("/search")
    public Result<List<ResourceClient.CourseResource>> searchResources(@RequestParam String topic) {
        return Result.success(resourceService.searchResources(topic));
    }

    @GetMapping("/search/advice")
    public Result<ResourceClient.ResourceAdviceResponse> searchResourcesWithAdvice(@RequestParam String topic) {
        return Result.success(resourceService.searchResourcesWithAdvice(topic));
    }

    @PostMapping("/search/advice/jobs")
    public Result<ResourceAdviceJobStartResponse> startAdviceJob(@RequestParam Long userId, @RequestBody ResourceAdviceJobStartRequest request) {
        return Result.success(resourceAdviceJobService.start(userId, request));
    }

    @GetMapping("/search/advice/jobs/{jobId}")
    public Result<ResourceAdviceJobStatusResponse> adviceJobStatus(@RequestParam Long userId, @PathVariable String jobId) {
        return Result.success(resourceAdviceJobService.status(userId, jobId));
    }
}
