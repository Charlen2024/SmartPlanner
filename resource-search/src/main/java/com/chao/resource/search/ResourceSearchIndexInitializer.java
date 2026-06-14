package com.chao.resource.search;

import com.chao.resource.entity.CourseResource;
import com.chao.resource.mapper.CourseResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ResourceSearchIndexInitializer implements ApplicationRunner {
    private final ElasticsearchOperations elasticsearchOperations;
    private final CourseResourceSearchRepository searchRepository;
    private final CourseResourceMapper courseResourceMapper;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private co.elastic.clients.elasticsearch.ElasticsearchClient esClient;

    @Value("${smartplanner.search.vector.reindex-on-startup:true}")
    private boolean reindexOnStartup;

    public ResourceSearchIndexInitializer(ElasticsearchOperations ops,
                                          CourseResourceSearchRepository repo,
                                          CourseResourceMapper mapper) {
        this.elasticsearchOperations = ops;
        this.searchRepository = repo;
        this.courseResourceMapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            IndexOperations ops = elasticsearchOperations.indexOps(CourseResourceDocument.class);

            if (ops.exists()) {
                boolean hasKnnEmbedding = false;
                try {
                    Map<String, Object> mapping = ops.getMapping();
                    if (mapping != null) {
                        String mappingStr = mapping.toString();
                        hasKnnEmbedding = mappingStr.contains("embedding") && mappingStr.contains("\"index\":true");
                    }
                } catch (Exception ignored) {
                }

                if (!hasKnnEmbedding && reindexOnStartup) {
                    log.info("Index lacks kNN-enabled embedding field. Recreating...");
                    ops.delete();
                    createIndexWithKnnMapping(ops);
                    log.info("Index recreated with kNN-enabled dense_vector mapping.");
                    seedFromDatabase();
                } else if (!hasKnnEmbedding) {
                    log.warn("Index lacks kNN-enabled embedding but reindex-on-startup is false.");
                    if (searchRepository.count() == 0) {
                        seedFromDatabase();
                    }
                } else {
                    if (searchRepository.count() == 0) {
                        seedFromDatabase();
                    }
                }
            } else {
                createIndexWithKnnMapping(ops);
                seedFromDatabase();
            }
        } catch (Exception e) {
            log.warn("resource-search: elasticsearch init skipped: {}", e.getMessage());
        }
    }

    private void seedFromDatabase() {
        List<CourseResource> all = courseResourceMapper.selectList(null);
        if (all == null || all.isEmpty()) {
            log.info("No resources in MySQL to seed.");
            return;
        }

        log.info("Seeding {} resources from MySQL to ES...", all.size());
        int embeddedCount = 0;
        int failedCount = 0;
        List<CourseResourceDocument> docs = new ArrayList<>();
        int batch = 0;
        for (CourseResource r : all) {
            CourseResourceDocument d = toDoc(r);
            boolean ok = generateAndSetEmbedding(d);
            if (ok) embeddedCount++; else failedCount++;
            docs.add(d);
            if (docs.size() >= 20) {
                searchRepository.saveAll(docs);
                docs.clear();
                batch++;
                log.info("Seeded batch {} ({} docs), embeddings: {}/{}", batch, batch * 20, embeddedCount, embeddedCount + failedCount);
                if (embeddingModel != null && batch > 0) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
            }
        }
        if (!docs.isEmpty()) {
            searchRepository.saveAll(docs);
        }
        long finalCount = searchRepository.count();
        log.info("Seed complete: {}/{} docs indexed with embeddings, {} total in ES", embeddedCount, all.size(), finalCount);
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

    private boolean generateAndSetEmbedding(CourseResourceDocument doc) {
        if (embeddingModel == null) return false;
        try {
            String text = buildEmbeddingText(doc.getTopic(), doc.getTitle(), doc.getContentSummary());
            if (text.isBlank()) return false;
            float[] vector = embeddingModel.embed(text);
            if (vector != null && vector.length > 0) {
                doc.setEmbedding(vector);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.debug("Reindex embedding failed for doc id={}: {}", doc.getId(), e.getMessage());
            return false;
        }
    }

    private void createIndexWithKnnMapping(IndexOperations ops) {
        if (esClient == null) {
            ops.createWithMapping();
            return;
        }
        try {
            esClient.indices().create(c -> c
                    .index("course_resources")
                    .mappings(m -> m
                            .properties("id", Property.of(p -> p.long_(l -> l)))
                            .properties("topic", Property.of(p -> p.text(t -> t)))
                            .properties("title", Property.of(p -> p.text(t -> t)))
                            .properties("platform", Property.of(p -> p.keyword(k -> k)))
                            .properties("sourceUrl", Property.of(p -> p.keyword(k -> k)))
                            .properties("contentSummary", Property.of(p -> p.text(t -> t)))
                            .properties("createdAtEpochMillis", Property.of(p -> p.long_(l -> l)))
                            .properties("embedding", Property.of(p -> p
                                    .denseVector(DenseVectorProperty.of(d -> d
                                            .dims(1536)
                                            .index(true)
                                            .similarity("cosine")))))));
        } catch (Exception e) {
            log.warn("Failed to create index with kNN mapping: {}", e.getMessage());
            ops.createWithMapping();
        }
    }

    private static String buildEmbeddingText(String topic, String title, String summary) {
        StringBuilder sb = new StringBuilder();
        if (topic != null && !topic.isBlank()) sb.append(topic).append(" ");
        if (title != null && !title.isBlank()) sb.append(title).append(" ");
        if (summary != null && !summary.isBlank()) sb.append(summary);
        String result = sb.toString().trim();
        if (result.length() > 6000) result = result.substring(0, 6000);
        return result;
    }
}
