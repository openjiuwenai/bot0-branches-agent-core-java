/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.retrieval.vector_store.provider;

import com.openjiuwen.retrieval.vector_store.adapter.PGVectorStore;
import com.openjiuwen.spi.store.vector.BaseVectorStore;
import com.openjiuwen.spi.store.vector.VectorStoreProvider;

import java.util.Map;

/**
 * Built-in vector store provider for PGVector.
 * <p>
 * Creates vector store instances backed by PostgreSQL with the pgvector extension,
 * enabling vector similarity search within a relational database environment.
 * 
 * @see VectorStoreProvider
 * @see com.openjiuwen.retrieval.vector_store.adapter.PGVectorStore
 * @since 0.1.7
 */
public final class PGVectorStoreProvider implements VectorStoreProvider {
    /**
     * typeName.
     * 
     * @return the result
     * @since 0.1.7
     */
    @Override
    public String typeName() {
        return "pgvector";
    }

    /**
     * Creates a new PGVector store instance.
     * 
     * @param conf the configuration map for PostgreSQL/pgvector connection
     * @return a new PGVectorStore instance
     * @since 0.1.7
     */
    @Override
    public BaseVectorStore create(Map<String, Object> conf) {
        return new PGVectorStore(conf);
    }
}
