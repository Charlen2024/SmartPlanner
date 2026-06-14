package com.chao.user.controller;

import com.chao.common.client.ResourceClient;
import com.chao.common.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceClient resourceClient;
    private final UserControllerSupport support;

    @GetMapping("/resources/search")
    public Result<List<SearchResourceItem>> searchResources(@RequestParam String topic) {
        return resourceClient.searchOnlineCourses(topic);
    }

    @PostMapping("/resources/crawl")
    public Result<String> crawlResources(
            @RequestParam String topic,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return resourceClient.crawlTopic(topic, support.resolveUserId(jwt, headerUserId, userId));
    }

    @GetMapping("/resources/search/advice")
    public Result<ResourceAdviceResult> searchResourcesWithAdvice(@RequestParam String topic) {
        return resourceClient.searchOnlineCoursesWithAdvice(topic);
    }

    @PostMapping("/resources/search/advice/jobs")
    public Result<ResourceAdviceJobStartResponse> startResourceAdviceJob(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestBody ResourceAdviceJobStartRequest request) {
        return resourceClient.startResourceAdviceJob(support.resolveUserId(jwt, headerUserId, userId), request);
    }

    @GetMapping("/resources/search/advice/jobs/{jobId}")
    public Result<ResourceAdviceJobStatusResponse> getResourceAdviceJobStatus(
            @PathVariable String jobId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return resourceClient.getResourceAdviceJobStatus(support.resolveUserId(jwt, headerUserId, userId), jobId);
    }

    @PostMapping("/resources")
    public Result<CourseResourceDto> createResource(
            @RequestParam String topic,
            @RequestParam String title,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false, name = "url") String url,
            @RequestParam(required = false, name = "summary") String summary) {
        return resourceClient.createResource(topic, title, platform, url, summary);
    }

    @GetMapping("/resources")
    public Result<List<CourseResourceDto>> listResources(@RequestParam(required = false) String topic) {
        return resourceClient.listResources(topic);
    }

    @GetMapping("/resources/{id}")
    public Result<CourseResourceDto> getResource(@PathVariable Long id) {
        return resourceClient.getResource(id);
    }

    @DeleteMapping("/resources/{id}")
    public Result<String> deleteResource(@PathVariable Long id) {
        return resourceClient.deleteResource(id);
    }
}
