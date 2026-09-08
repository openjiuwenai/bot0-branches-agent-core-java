/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.harness.rails.interrupt;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.RailSettledDecision;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptionState;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Harness-level base rail for interrupt and resume handling.
 * Mirrors Python's openjiuwen.harness.rails.interrupt.interrupt_base.
 * 
 * @since 0.1.7
 */
public abstract class BaseInterruptRail extends AgentRail {
    private final Set<String> toolNames = new LinkedHashSet<String>();

    /**
     * Create an interrupt rail for a set of tool names.
     * 
     * @param toolNames intercepted tool names
     * @since 0.1.7
     */
    protected BaseInterruptRail(Iterable<String> toolNames) {
        if (toolNames != null) {
            for (String toolName : toolNames) {
                if (toolName != null && !toolName.isEmpty()) {
                    this.toolNames.add(toolName);
                }
            }
        }
        setPriority(90);
    }

    /**
     * Approve tool execution without overriding arguments.
     * 
     * @return approve decision
     * @since 0.1.7
     */
    public ApproveResult approve() {
        return new ApproveResult(null);
    }

    /**
     * Approve tool execution and override the tool arguments.
     * 
     * @param newArgs rewritten tool arguments
     * @return approve decision
     * @since 0.1.7
     */
    public ApproveResult approve(String newArgs) {
        return new ApproveResult(newArgs);
    }

    /**
     * Reject tool execution with a synthetic tool result.
     * 
     * @param toolResult synthetic tool result
     * @return reject decision
     * @since 0.1.7
     */
    public RejectResult reject(Object toolResult) {
        return new RejectResult(toolResult, null);
    }

    /**
     * Reject tool execution with a synthetic tool result and message.
     * 
     * @param toolResult synthetic tool result
     * @param toolMessage synthetic tool message
     * @return reject decision
     * @since 0.1.7
     */
    public RejectResult reject(Object toolResult, ToolMessage toolMessage) {
        return new RejectResult(toolResult, toolMessage);
    }

    /**
     * Interrupt tool execution and wait for user input.
     * 
     * @param request interruption payload
     * @return interrupt decision
     * @since 0.1.7
     */
    public InterruptResult interrupt(InterruptRequest request) {
        return new InterruptResult(request);
    }

    /**
     * Register a tool name intercepted by this rail.
     * 
     * @param toolName tool name
     * @since 0.1.7
     */
    public void addTool(String toolName) {
        if (toolName != null && !toolName.isEmpty()) {
            toolNames.add(toolName);
        }
    }

    /**
     * Register multiple tool names intercepted by this rail.
     * 
     * @param toolNames tool names
     * @since 0.1.7
     */
    public void addTools(Iterable<String> toolNames) {
        if (toolNames == null) {
            return;
        }
        for (String toolName : toolNames) {
            addTool(toolName);
        }
    }

    /**
     * Backward-compatible alias of {@link #addTool(String)}.
     * 
     * @param toolName tool name
     * @param ignoredPolicy ignored compatibility parameter
     * @since 0.1.7
     */
    public void addPolicy(String toolName, Object ignoredPolicy) {
        addTool(toolName);
    }

    /**
     * Return all intercepted tool names.
     * 
     * @return intercepted tool names
     * @since 0.1.7
     */
    public Set<String> getToolNames() {
        return new LinkedHashSet<String>(toolNames);
    }

    /**
     * beforeToolCall.
     * 
     * @param ctx ctx
     * @since 0.1.7
     */
    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs)) {
            return;
        }
        ToolCallInputs inputs = (ToolCallInputs) ctx.getInputs();
        String toolName = inputs.getToolName();
        if (!toolNames.contains(toolName)) {
            return;
        }
        evaluateWithSettledReplay(ctx, inputs);
    }

    /**
     * Shared before-tool-call flow with settled-decision replay support. If this rail already
     * issued a final verdict (approve or reject) for the current tool call during an earlier
     * replay round, the persisted verdict is re-applied without consulting the rail again.
     * Otherwise the rail is consulted through {@link #resolveInterrupt} and a final verdict is
     * recorded for later replays.
     * 
     * @param ctx ctx
     * @param inputs inputs
     * @since 0.1.16
     */
    protected void evaluateWithSettledReplay(AgentCallbackContext ctx, ToolCallInputs inputs) {
        ToolCall toolCall = inputs.getToolCall();
        String toolCallId = toolCall != null ? toolCall.getId() : "";

        RailSettledDecision settled = findSettledDecision(ctx, toolCallId);
        if (settled != null) {
            applyDecision(ctx, toolCall, toDecision(settled));
            return;
        }

        Object userInput = getUserInput(ctx, toolCallId);
        InterruptDecision decision = resolveInterrupt(ctx, toolCall, userInput);
        recordSettledDecision(ctx, toolCallId, decision);
        applyDecision(ctx, toolCall, decision);
    }

    /**
     * Return the stable identity used to persist and replay this rail's settled decisions.
     * Defaults to the concrete class name; override it when multiple instances of the same rail
     * class are registered on one agent.
     * 
     * @return stable identity of this rail
     * @since 0.1.16
     */
    protected String railId() {
        return getClass().getName();
    }

    /**
     * Find the verdict this rail already issued for the given tool call during an earlier
     * replay round.
     * 
     * @param ctx ctx
     * @param toolCallId toolCallId
     * @return the persisted verdict, or null when this rail has not settled the tool call yet
     * @since 0.1.16
     */
    @SuppressWarnings("unchecked")
    private RailSettledDecision findSettledDecision(AgentCallbackContext ctx, String toolCallId) {
        if (ctx.getExtra() == null || toolCallId == null) {
            return null;
        }
        Object raw = ctx.getExtra().get(ToolInterruptionState.RAIL_SETTLED_DECISIONS_KEY);
        if (!(raw instanceof Map<?, ?> byToolCallId)) {
            return null;
        }
        Object perRail = byToolCallId.get(toolCallId);
        if (!(perRail instanceof Map<?, ?> byRailId)) {
            return null;
        }
        Object settled = byRailId.get(railId());
        return settled instanceof RailSettledDecision decision ? decision : null;
    }

    /**
     * Record a final verdict (approve or reject) issued by this rail for the given tool call so
     * that later replays re-apply it without consulting this rail again. Interrupt decisions are
     * not final and are not recorded.
     * 
     * @param ctx ctx
     * @param toolCallId toolCallId
     * @param decision decision
     * @since 0.1.16
     */
    @SuppressWarnings("unchecked")
    private void recordSettledDecision(AgentCallbackContext ctx, String toolCallId, InterruptDecision decision) {
        RailSettledDecision settled = toSettledDecision(railId(), decision);
        if (settled == null || ctx.getExtra() == null) {
            return;
        }
        Object raw = ctx.getExtra().get(ToolInterruptionState.RAIL_SETTLED_DECISIONS_KEY);
        if (raw instanceof Map<?, ?> existing) {
            ((Map<String, Map<String, RailSettledDecision>>) existing)
                    .computeIfAbsent(toolCallId, key -> new ConcurrentHashMap<>())
                    .put(settled.getRailId(), settled);
        } else {
            Map<String, Map<String, RailSettledDecision>> byToolCallId = new ConcurrentHashMap<>();
            byToolCallId.computeIfAbsent(toolCallId, key -> new ConcurrentHashMap<>())
                    .put(settled.getRailId(), settled);
            ctx.getExtra().put(ToolInterruptionState.RAIL_SETTLED_DECISIONS_KEY, byToolCallId);
        }
    }

    /**
     * Convert a rail decision into a persistable settled verdict; interrupt decisions yield null.
     * 
     * @param railId railId
     * @param decision decision
     * @return the persistable verdict, or null when the decision is not final
     * @since 0.1.16
     */
    private static RailSettledDecision toSettledDecision(String railId, InterruptDecision decision) {
        if (decision instanceof ApproveResult) {
            ApproveResult approveResult = (ApproveResult) decision;
            return RailSettledDecision.builder().railId(railId).type(RailSettledDecision.TYPE_APPROVE)
                    .newArgs(approveResult.getNewArgs()).build();
        }
        if (decision instanceof RejectResult) {
            RejectResult rejectResult = (RejectResult) decision;
            Object toolResult = rejectResult.getToolResult();
            return RailSettledDecision.builder().railId(railId).type(RailSettledDecision.TYPE_REJECT)
                    .toolResult(toolResult != null ? String.valueOf(toolResult) : null)
                    .toolMessage(rejectResult.getToolMessage()).build();
        }
        return null;
    }

    /**
     * Rebuild the rail decision from a persisted settled verdict.
     * 
     * @param settled settled
     * @return the rebuilt decision
     * @since 0.1.16
     */
    private static InterruptDecision toDecision(RailSettledDecision settled) {
        if (RailSettledDecision.TYPE_APPROVE.equals(settled.getType())) {
            return new ApproveResult(settled.getNewArgs());
        }
        return new RejectResult(settled.getToolResult(), settled.getToolMessage());
    }

    /**
     * Resolve the decision for the current tool invocation.
     * 
     * @param ctx callback context
     * @param toolCall current tool call
     * @param userInput resume input, when present
     * @return rail decision
     * @since 0.1.7
     */
    protected abstract InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall,
            Object userInput);

    /**
     * Extract resume input for the current tool call from callback context.
     * 
     * @param ctx callback context
     * @param toolCallId current tool call id
     * @return matched user input or null-equivalent object
     * @since 0.1.7
     */
    protected Object getUserInput(AgentCallbackContext ctx, String toolCallId) {
        Object rawInput = ctx.getExtra().get(ToolInterruptionState.RESUME_USER_INPUT_KEY);
        Object resolvedInput = rawInput;
        if (rawInput == null) {
            return resolvedInput;
        }
        if (rawInput instanceof InteractiveInput) {
            InteractiveInput interactiveInput = (InteractiveInput) rawInput;
            Map<String, Object> userInputs = interactiveInput.getUserInputs();
            if (toolCallId != null && !toolCallId.isEmpty() && userInputs.containsKey(toolCallId)) {
                return userInputs.get(toolCallId);
            }
            return interactiveInput.getRawInputs();
        }
        if (rawInput instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) rawInput;
            if (toolCallId != null && !toolCallId.isEmpty() && map.containsKey(toolCallId)) {
                return map.get(toolCallId);
            }
            return rawInput;
        }
        return rawInput;
    }

    /**
     * applyDecision.
     * 
     * @param ctx ctx
     * @param toolCall toolCall
     * @param decision decision
     * @since 0.1.7
     */
    private void applyDecision(AgentCallbackContext ctx, ToolCall toolCall, InterruptDecision decision) {
        ToolCallInputs inputs = null;
        if (ctx.getInputs() instanceof ToolCallInputs) {
            inputs = (ToolCallInputs) ctx.getInputs();
        }
        if (inputs == null) {
            return;
        }
        if (decision instanceof ApproveResult) {
            ApproveResult approveResult = (ApproveResult) decision;
            if (approveResult.getNewArgs() != null) {
                inputs.setToolArgs(approveResult.getNewArgs());
            }
            return;
        }
        if (decision instanceof RejectResult) {
            RejectResult rejectResult = (RejectResult) decision;
            ctx.getExtra().put("_skip_tool", Boolean.TRUE);
            inputs.setToolResult(rejectResult.getToolResult());
            ToolMessage toolMessage = rejectResult.getToolMessage();
            if (toolMessage == null) {
                String toolCallId = toolCall != null ? toolCall.getId() : "";
                toolMessage = ToolMessage.builder().content(String.valueOf(rejectResult.getToolResult()))
                        .toolCallId(toolCallId).build();
            }
            inputs.setToolMsg(toolMessage);
            return;
        }
        if (decision instanceof InterruptResult) {
            InterruptResult interruptResult = (InterruptResult) decision;
            throw new ToolInterruptException(interruptResult.getRequest(), toolCall);
        }
    }
}
