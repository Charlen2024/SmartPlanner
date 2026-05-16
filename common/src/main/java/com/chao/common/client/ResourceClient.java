package com.chao.common.client;

import com.chao.common.dto.Result;
import com.chao.common.dto.CourseResourceDto;
import com.chao.common.dto.ResourceAdviceJobStartRequest;
import com.chao.common.dto.ResourceAdviceJobStartResponse;
import com.chao.common.dto.ResourceAdviceJobStatusResponse;
import lombok.Data;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "resource-search")
public interface ResourceClient {
    @PostMapping("/api/resources")
    Result<CourseResourceDto> createResource(
            @RequestParam("topic") String topic,
            @RequestParam("title") String title,
            @RequestParam(value = "platform", required = false) String platform,
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "summary", required = false) String summary);

    @GetMapping("/api/resources")
    Result<List<CourseResourceDto>> listResources(@RequestParam(value = "topic", required = false) String topic);

    @GetMapping("/api/resources/{id}")
    Result<CourseResourceDto> getResource(@PathVariable("id") Long id);

    @DeleteMapping("/api/resources/{id}")
    Result<String> deleteResource(@PathVariable("id") Long id);

    @GetMapping("/api/resources/search")
    Result<List<CourseResource>> searchOnlineCourses(@RequestParam("topic") String topic);

    @GetMapping("/api/resources/search/advice")
    Result<ResourceAdviceResponse> searchOnlineCoursesWithAdvice(@RequestParam("topic") String topic);

    @PostMapping("/api/resources/search/advice/jobs")
    Result<ResourceAdviceJobStartResponse> startResourceAdviceJob(
            @RequestParam("userId") Long userId,
            @RequestBody ResourceAdviceJobStartRequest request);

    @GetMapping("/api/resources/search/advice/jobs/{jobId}")
    Result<ResourceAdviceJobStatusResponse> getResourceAdviceJobStatus(
            @RequestParam("userId") Long userId,
            @PathVariable("jobId") String jobId);

    @Data
    class CourseResource {
        private String title;
        private String platform;
        private String url;
        private String summary;
    }

    @Data
    class ResourceAdviceResponse {
        private String topic;
        private String advice;
        private List<CourseResource> resources;
    }
}
