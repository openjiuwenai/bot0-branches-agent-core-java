/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.retrieval.vector_store.adapter;

import java.util.Map;

/**
 * Shared option parsing for external vector-store adapters.
 *
 * @since 0.1.15
 */
final class AdapterOptions {
    private AdapterOptions() {
    }

    static String indexType(Map<String, Object> options) {
        return stringOption(options, "index_type", "indexType", "hybrid");
    }

    static String stringOption(Map<String, Object> options, String key, String alternateKey, String fallback) {
        Object value = null;
        if (options != null) {
            value = options.containsKey(key) ? options.get(key) : options.get(alternateKey);
        }
        return value == null ? fallback : String.valueOf(value);
    }
}
