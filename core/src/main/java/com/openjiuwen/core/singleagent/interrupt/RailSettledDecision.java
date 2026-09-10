/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.core.singleagent.interrupt;

import com.openjiuwen.core.foundation.llm.schema.ToolMessage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * A final verdict (approve or reject) that a rail has already issued for a tool call. Persisted
 * alongside the interruption state so that later interrupt replays can re-apply the verdict
 * without consulting the rail again.
 *
 * @since 0.1.16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RailSettledDecision implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * TYPE_APPROVE.
     *
     * @since 0.1.16
     */
    public static final String TYPE_APPROVE = "approve";

    /**
     * TYPE_REJECT.
     *
     * @since 0.1.16
     */
    public static final String TYPE_REJECT = "reject";

    /**
     * Stable identity of the rail that issued the verdict.
     *
     * @since 0.1.16
     */
    private String railId;

    /**
     * Verdict type, either {@link #TYPE_APPROVE} or {@link #TYPE_REJECT}.
     *
     * @since 0.1.16
     */
    private String type;

    /**
     * Overridden tool arguments carried by an approve verdict, or null.
     *
     * @since 0.1.16
     */
    private String newArgs;

    /**
     * Rendered synthetic tool result carried by a reject verdict, or null. The original object
     * is rendered with {@code String.valueOf} to keep the persisted state serializable.
     *
     * @since 0.1.16
     */
    private String toolResult;

    /**
     * Synthetic tool message carried by a reject verdict, or null.
     *
     * @since 0.1.16
     */
    private ToolMessage toolMessage;
}
