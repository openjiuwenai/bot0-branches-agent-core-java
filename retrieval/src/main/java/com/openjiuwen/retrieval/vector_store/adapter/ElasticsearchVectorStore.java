/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.retrieval.vector_store.adapter;

import com.openjiuwen.core.retrieval.common.VectorStoreConfig;

import java.util.Map;

/**
 * Foundation-store Elasticsearch adapter.
 * <p>
 * Delegates to the retrieval ElasticsearchVectorStore REST backend.
 * </p>
 * 
 * @since 0.1.7
 */
public class ElasticsearchVectorStore extends AbstractRetrievalVectorStoreAdapter {
    /**
     * ElasticsearchVectorStore.
     * 
     * @param options options
     * @since 0.1.7
     */
    public ElasticsearchVectorStore(Map<String, Object> options) {
        super(new com.openjiuwen.retrieval.vector_store.ElasticsearchVectorStore(config(options),
                AdapterOptions.indexType(options)));
    }

    /**
     * config.
     * 
     * @param options options
     * @return the result
     * @since 0.1.7
     */
    private static VectorStoreConfig config(Map<String, Object> options) {
        return new VectorStoreConfig("elasticsearch",
                AdapterOptions.stringOption(options, "database_name", "databaseName", ""),
                AdapterOptions.stringOption(options, "collection_name", "collectionName", "default_collection"),
                AdapterOptions.stringOption(options, "distance_metric", "distanceMetric", "cosine"));
    }
}
