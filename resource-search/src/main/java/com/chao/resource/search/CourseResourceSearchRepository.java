package com.chao.resource.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface CourseResourceSearchRepository extends ElasticsearchRepository<CourseResourceDocument, Long> {
}
