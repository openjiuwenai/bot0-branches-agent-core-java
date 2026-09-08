/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.spi.store.vector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openjiuwen.core.foundation.store.vector.InMemoryVectorStore;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Verifies the vector-store providers available from the Core artifact alone.
 *
 * @since 0.1.15
 */
class VectorStoreFactoryCoreTest {
    @Test
    void coreServiceLoaderProvidesOnlyInMemoryStore() {
        List<String> providerTypes = ServiceLoader.load(VectorStoreProvider.class).stream()
                .map(provider -> provider.get().typeName())
                .toList();

        assertTrue(providerTypes.contains("in_memory"));
        assertFalse(providerTypes.contains("chroma"));
        assertFalse(providerTypes.contains("milvus"));
        assertFalse(providerTypes.contains("pgvector"));
        assertFalse(providerTypes.contains("elasticsearch"));
    }

    @Test
    void coreFactoryCreatesInMemoryStoreAndAlias() {
        assertInstanceOf(InMemoryVectorStore.class, VectorStoreFactory.create("in_memory"));
        assertInstanceOf(InMemoryVectorStore.class, VectorStoreFactory.create("memory"));
    }
}
