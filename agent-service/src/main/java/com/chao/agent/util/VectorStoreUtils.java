package com.chao.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;

public final class VectorStoreUtils {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreUtils.class);

    private VectorStoreUtils() {}

    public static void addDocsInBatches(VectorStore vectorStore, List<Document> docs, int batchSize) {
        if (vectorStore == null || docs == null || docs.isEmpty()) return;
        int size = Math.max(1, Math.min(batchSize, 25));
        for (int i = 0; i < docs.size(); i += size) {
            List<Document> part = docs.subList(i, Math.min(docs.size(), i + size));
            vectorStore.add(part);
        }
    }

    public static void deleteByUserId(VectorStore vectorStore, Long userId) {
        if (vectorStore == null || userId == null) return;
        try {
            vectorStore.delete(new FilterExpressionBuilder().eq("userId", userId).build());
        } catch (Exception e) {
            log.warn("Failed to delete vector store docs for userId={}: {}", userId, e.getMessage());
        }
    }
}
