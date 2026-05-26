package com.chao.resource.search;

import com.chao.resource.entity.CourseResource;
import com.chao.resource.mapper.CourseResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceSearchIndexInitializer implements ApplicationRunner {
    private final ElasticsearchOperations elasticsearchOperations;
    private final CourseResourceSearchRepository searchRepository;
    private final CourseResourceMapper courseResourceMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            IndexOperations ops = elasticsearchOperations.indexOps(CourseResourceDocument.class);
            if (!ops.exists()) {
                ops.createWithMapping();
            }

            long existing = searchRepository.count();
            if (existing > 0) {
                return;
            }

            List<CourseResource> all = courseResourceMapper.selectList(null);
            if (all == null || all.isEmpty()) {
                return;
            }

            List<CourseResourceDocument> docs = all.stream().map(this::toDoc).toList();
            searchRepository.saveAll(docs);
        } catch (Exception e) {
            log.warn("resource-search: elasticsearch init skipped: {}", e.getMessage());
        }
    }

    private CourseResourceDocument toDoc(CourseResource r) {
        CourseResourceDocument d = new CourseResourceDocument();
        d.setId(r.getId());
        d.setTopic(r.getTopic());
        d.setTitle(r.getTitle());
        d.setPlatform(r.getPlatform());
        d.setSourceUrl(r.getSourceUrl());
        d.setContentSummary(r.getContentSummary());
        d.setCreatedAtEpochMillis(r.getCreatedAt() != null ? r.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
        return d;
    }
}
