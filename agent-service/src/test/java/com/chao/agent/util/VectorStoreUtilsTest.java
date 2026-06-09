package com.chao.agent.util;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class VectorStoreUtilsTest {

    private static Object nullDefault(Class<?> rt) {
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        return null;
    }

    @Test
    void addDocsInBatches_shouldSplitIntoBatches() {
        List<Document> addedDocs = new ArrayList<>();
        VectorStore vs = (VectorStore) Proxy.newProxyInstance(
                VectorStore.class.getClassLoader(),
                new Class[]{VectorStore.class},
                (proxy, method, args) -> {
                    if ("add".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Document> batch = (List<Document>) args[0];
                        addedDocs.addAll(batch);
                    }
                    return nullDefault(method.getReturnType());
                }
        );

        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            docs.add(new Document("id:" + i, "text " + i, Map.of()));
        }

        VectorStoreUtils.addDocsInBatches(vs, docs, 20);

        assertEquals(55, addedDocs.size());
        assertEquals("text 0", addedDocs.get(0).getText());
        assertEquals("text 54", addedDocs.get(54).getText());
    }

    @Test
    void addDocsInBatches_shouldHandleEmpty() {
        List<Document> addedDocs = new ArrayList<>();
        VectorStore vs = (VectorStore) Proxy.newProxyInstance(
                VectorStore.class.getClassLoader(),
                new Class[]{VectorStore.class},
                (proxy, method, args) -> {
                    if ("add".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Document> batch = (List<Document>) args[0];
                        addedDocs.addAll(batch);
                    }
                    return nullDefault(method.getReturnType());
                }
        );

        VectorStoreUtils.addDocsInBatches(vs, List.of(), 20);
        assertEquals(0, addedDocs.size());
    }

    @Test
    void addDocsInBatches_shouldNotThrowOnNull() {
        VectorStoreUtils.addDocsInBatches(null, List.of(new Document("id", "text", Map.of())), 20);
    }

    @Test
    void deleteByUserId_shouldPassFilterExpression() {
        AtomicReference<String> filterStr = new AtomicReference<>();
        VectorStore vs = (VectorStore) Proxy.newProxyInstance(
                VectorStore.class.getClassLoader(),
                new Class[]{VectorStore.class},
                (proxy, method, args) -> {
                    if ("delete".equals(method.getName()) && args != null && args.length > 0
                            && args[0] instanceof Filter.Expression expr) {
                        filterStr.set(expr.toString());
                    }
                    return nullDefault(method.getReturnType());
                }
        );

        VectorStoreUtils.deleteByUserId(vs, 42L);

        String f = filterStr.get();
        assertNotNull(f, "delete should be called with a filter expression");
        assertTrue(f.contains("userId"), "filter should reference userId field: " + f);
        assertTrue(f.contains("42"), "filter should contain userId=42: " + f);
    }

    @Test
    void deleteByUserId_shouldNotThrowOnNull() {
        VectorStoreUtils.deleteByUserId(null, 1L);
        VectorStoreUtils.deleteByUserId(null, null);
    }
}
