/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.core.singleagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.AudioGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ImageGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.VideoGenerationResponse;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 回归测试：中断重放时已判定 Rail 的终局判定必须被正确回放，而非简单跳过。
 *
 * <p>覆盖四个场景。每个场景构造两条 Rail 作用于同一次工具调用，验证中断重放时
 * 前置 Rail 的终局判定被正确回放（而非重新征询），后置 Rail 能如期收到恢复输入。</p>
 *
 * <p>前三组通过让后置 Rail 在收到远端结果后 approve（放行工具执行），使得工具真正执行
 * ——此时前置 Rail 的 approve(newArgs) 参数覆写是否被回放、reject 的 _skip_tool 是否
 * 被重新注入，就能通过工具实际收到的参数和执行次数来区分"回放"与"跳过"两种方案。</p>
 *
 * <p>第四组复现 issue #163 原始场景：前置闸门 approve 放行后，后置 Rail 收到远端结果
 * 时 reject（合成工具结果回喂）。若前置闸门被重复征询，会抢先抛中断，远端结果无法
 * 到达后置 Rail。</p>
 *
 * @since 0.1.16
 */
class InterruptReplaySettledDecisionReplayTest {
    private static final String TEST_PROVIDER = "InterruptReplaySettledDecisionReplay";
    private static final String SESSION_ID = "settled-replay-session";
    private static final String AGENT_ID = "settled-replay-agent";
    private static final String TOOL_NAME = "guarded_task";
    private static final String TOOL_CALL_ID = "settled-tool-call";
    private static final AtomicBoolean FACTORY_REGISTERED = new AtomicBoolean(false);

    private final AtomicReference<String> toolReceivedArgs = new AtomicReference<>("NEVER_CALLED");
    private final AtomicInteger toolCallCount = new AtomicInteger(0);

    InterruptReplaySettledDecisionReplayTest() {
        if (FACTORY_REGISTERED.compareAndSet(false, true)) {
            Model.registerFactory(new DemoModelFactory());
        }
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
        Runner.setConfig(RunnerConfig.DEFAULT);
    }

    @AfterEach
    void cleanup() {
        Runner.resourceMgr().removeTool(TOOL_NAME, AGENT_ID,
                com.openjiuwen.core.runner.base.TagMatchStrategy.ALL, true);
        CheckpointerFactory.getCheckpointer().release(SESSION_ID);
        Runner.release(SESSION_ID);
        Runner.stop();
    }

    @Test
    void approveWithArgsOverrideIsReplayedWhenDownstreamRailInterrupts() {
        ReActAgent agent = newAgent();
        ArgsGateRail gate = new ArgsGateRail();
        HandoffApproveRail handoff = new HandoffApproveRail();
        AgentSessionApi session = AgentSessionApi.create(SESSION_ID, null, agent.getCard());
        agent.registerRail(gate);
        agent.registerRail(handoff);
        Runner.resourceMgr().addTool(createTool(), agent.getCard().getId());

        // 第 1 轮：闸门要求确认
        invoke(agent, session, Map.of("query", "start", "conversation_id", SESSION_ID));
        assertThat(gate.askCount).as("第 1 轮：闸门要求确认一次").isEqualTo(1);

        // 第 2 轮：用户确认 → 闸门 approve(覆写参数) → 后置 Rail 交接中断
        invoke(agent, session, Map.of("query", resumeWith(new Confirmation(true)), "conversation_id", SESSION_ID));
        assertThat(gate.askCount).as("第 2 轮：闸门放行").isEqualTo(1);
        assertThat(handoff.handoffCount).as("第 2 轮：后置 Rail 交接").isEqualTo(1);

        // 第 3 轮：远端结果回来 → 后置 Rail approve 放行 → 工具执行
        // 重放时闸门的 approve(newArgs) 必须被回放，工具才能收到覆写后的参数
        invoke(agent, session, Map.of("query", resumeWith(new RemoteResult("REMOTE_RESULT")), "conversation_id", SESSION_ID));

        org.assertj.core.api.SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(gate.askCount)
                    .as("第 3 轮：闸门不被重复征询")
                    .isEqualTo(1);
            softly.assertThat(toolCallCount.get())
                    .as("第 3 轮：工具被执行一次")
                    .isEqualTo(1);
            softly.assertThat(toolReceivedArgs.get())
                    .as("第 3 轮：approve 的参数覆写被回放（跳过方案会用原始参数 {}）")
                    .contains("sanitized");
        });
    }

    @Test
    void rejectWithSyntheticResultIsReplayedWhenDownstreamRailInterrupts() {
        ReActAgent agent = newAgent();
        RejectGateRail gate = new RejectGateRail();
        HandoffApproveRail handoff = new HandoffApproveRail();
        AgentSessionApi session = AgentSessionApi.create(SESSION_ID, null, agent.getCard());
        agent.registerRail(gate);
        agent.registerRail(handoff);
        Runner.resourceMgr().addTool(createTool(), agent.getCard().getId());

        // 第 1 轮：闸门要求确认
        invoke(agent, session, Map.of("query", "start", "conversation_id", SESSION_ID));
        assertThat(gate.askCount).as("第 1 轮：闸门要求确认一次").isEqualTo(1);

        // 第 2 轮：用户拒绝 → 闸门 reject(合成结果) → 后置 Rail 交接中断
        invoke(agent, session, Map.of("query", resumeWith(new Confirmation(false)), "conversation_id", SESSION_ID));
        assertThat(gate.rejectCount).as("第 2 轮：闸门拒绝").isEqualTo(1);
        assertThat(handoff.handoffCount).as("第 2 轮：后置 Rail 交接").isEqualTo(1);

        // 第 3 轮：远端结果回来 → 重放时 reject 的 _skip_tool 必须被重新注入
        // 跳过方案不重新设置 _skip_tool → 工具被错误执行
        invoke(agent, session, Map.of("query", resumeWith(new RemoteResult("REMOTE_RESULT")), "conversation_id", SESSION_ID));

        org.assertj.core.api.SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(gate.rejectCount)
                    .as("第 3 轮：闸门不被重复征询")
                    .isEqualTo(1);
            softly.assertThat(toolCallCount.get())
                    .as("第 3 轮：工具不应执行（reject 应被回放，_skip_tool 重新设置；跳过方案会错误执行工具）")
                    .isEqualTo(0);
        });
    }

    @Test
    void overriddenBeforeToolCallRailAlsoReplaysSettledDecision() {
        ReActAgent agent = newAgent();
        OverriddenGateRail gate = new OverriddenGateRail();
        HandoffApproveRail handoff = new HandoffApproveRail();
        AgentSessionApi session = AgentSessionApi.create(SESSION_ID, null, agent.getCard());
        agent.registerRail(gate);
        agent.registerRail(handoff);
        Runner.resourceMgr().addTool(createTool(), agent.getCard().getId());

        // 第 1 轮：闸门要求确认
        invoke(agent, session, Map.of("query", "start", "conversation_id", SESSION_ID));
        assertThat(gate.askCount).as("第 1 轮：闸门要求确认一次").isEqualTo(1);

        // 第 2 轮：用户确认 → 闸门 approve → 后置 Rail 交接中断
        invoke(agent, session, Map.of("query", resumeWith(new Confirmation(true)), "conversation_id", SESSION_ID));
        assertThat(gate.askCount).as("第 2 轮：闸门放行").isEqualTo(1);
        assertThat(handoff.handoffCount).as("第 2 轮：后置 Rail 交接").isEqualTo(1);

        // 第 3 轮：远端结果回来 → 覆写 beforeToolCall 的 Rail 也必须走共享回放流程
        invoke(agent, session, Map.of("query", resumeWith(new RemoteResult("REMOTE_RESULT")), "conversation_id", SESSION_ID));

        org.assertj.core.api.SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(gate.askCount)
                    .as("第 3 轮：覆写 beforeToolCall 的闸门不被重复征询（d700bb741 不覆盖此类）")
                    .isEqualTo(1);
            softly.assertThat(toolCallCount.get())
                    .as("第 3 轮：工具被执行一次")
                    .isEqualTo(1);
        });
    }

    @Test
    void gateApprovalIsReplayedWhenDownstreamRailRejectsWithRemoteResult() {
        ReActAgent agent = newAgent();
        SimpleGateRail gate = new SimpleGateRail();
        HandoffRejectRail handoff = new HandoffRejectRail();
        AgentSessionApi session = AgentSessionApi.create(SESSION_ID, null, agent.getCard());
        agent.registerRail(gate);
        agent.registerRail(handoff);
        Runner.resourceMgr().addTool(createTool(), agent.getCard().getId());

        // 第 1 轮：闸门要求确认
        invoke(agent, session, Map.of("query", "start", "conversation_id", SESSION_ID));
        assertThat(gate.askCount).as("第 1 轮：闸门要求确认一次").isEqualTo(1);

        // 第 2 轮：用户确认 → 闸门放行 → 后置 Rail 交接中断
        invoke(agent, session, Map.of("query", resumeWith(new Confirmation(true)), "conversation_id", SESSION_ID));
        assertThat(gate.askCount).as("第 2 轮：闸门放行").isEqualTo(1);
        assertThat(handoff.handoffCount).as("第 2 轮：后置 Rail 交接").isEqualTo(1);

        // 第 3 轮：远端结果回来 → 后置 Rail reject(远端结果) 作为工具结果回喂
        // 重放时闸门的 approve 必须被回放，后置 Rail 才能收到远端结果
        invoke(agent, session, Map.of("query", resumeWith(new RemoteResult("REMOTE_RESULT")), "conversation_id", SESSION_ID));

        org.assertj.core.api.SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(gate.askCount)
                    .as("第 3 轮：闸门不被重复征询（终局判定已回放）")
                    .isEqualTo(1);
            softly.assertThat(handoff.consumedRemoteResult)
                    .as("第 3 轮：后置 Rail 消费远端结果（闸门未抢先中断）")
                    .isEqualTo("REMOTE_RESULT");
            softly.assertThat(toolCallCount.get())
                    .as("第 3 轮：工具不应执行（后置 Rail reject 跳过工具）")
                    .isEqualTo(0);
        });
    }

    // ────────────────────────── Rail 链 ──────────────────────────

    /** 闸门：收到确认后 approve 并覆写参数。 */
    private static final class ArgsGateRail extends BaseInterruptRail {
        private int askCount;

        private ArgsGateRail() {
            super(List.of(TOOL_NAME));
            setPriority(190);
        }

        @Override
        protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
            if (userInput instanceof Confirmation confirmation && confirmation.approved()) {
                return approve("{\"sanitized\":true}");
            }
            if (userInput instanceof Confirmation) {
                return reject("user refused");
            }
            askCount++;
            return interrupt(InterruptRequest.builder().interruptId("gate-" + askCount)
                    .message("please confirm " + TOOL_NAME).build());
        }
    }

    /** 闸门：收到拒绝后 reject 并注入合成结果。 */
    private static final class RejectGateRail extends BaseInterruptRail {
        private int askCount;
        private int rejectCount;

        private RejectGateRail() {
            super(List.of(TOOL_NAME));
            setPriority(190);
        }

        @Override
        protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
            if (userInput instanceof Confirmation confirmation) {
                if (confirmation.approved()) {
                    return approve();
                }
                rejectCount++;
                return reject("NOT_ALLOWED");
            }
            askCount++;
            return interrupt(InterruptRequest.builder().interruptId("gate-" + askCount)
                    .message("please confirm " + TOOL_NAME).build());
        }
    }

    /** 闸门：覆写 beforeToolCall（模拟 PermissionInterruptRail 的模式），但委托共享流程。 */
    private static final class OverriddenGateRail extends BaseInterruptRail {
        private int askCount;

        private OverriddenGateRail() {
            super(null);
            setPriority(190);
            addTool(TOOL_NAME);
        }

        @Override
        public void beforeToolCall(AgentCallbackContext ctx) {
            if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
                return;
            }
            evaluateWithSettledReplay(ctx, inputs);
        }

        @Override
        protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
            if (userInput instanceof Confirmation confirmation) {
                return confirmation.approved() ? approve() : reject("user refused");
            }
            askCount++;
            return interrupt(InterruptRequest.builder().interruptId("gate-" + askCount)
                    .message("please confirm " + TOOL_NAME).build());
        }
    }

    /** 后置交接：首次把工作交给远端，收到远端结果后 approve 放行工具执行。 */
    private static final class HandoffApproveRail extends BaseInterruptRail {
        private int handoffCount;

        private HandoffApproveRail() {
            super(List.of(TOOL_NAME));
            setPriority(85);
        }

        @Override
        protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
            if (userInput instanceof RemoteResult) {
                return approve();
            }
            handoffCount++;
            return interrupt(InterruptRequest.builder().interruptId("handoff-" + handoffCount)
                    .message("handing off " + TOOL_NAME).build());
        }
    }

    /** 前置闸门：首次要求确认，拿到确认应答后放行。优先级高于后置 Rail。 */
    private static final class SimpleGateRail extends BaseInterruptRail {
        private int askCount;

        private SimpleGateRail() {
            super(List.of(TOOL_NAME));
            setPriority(190);
        }

        @Override
        protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
            if (userInput instanceof Confirmation confirmation) {
                return confirmation.approved() ? approve() : reject("user refused");
            }
            askCount++;
            return interrupt(InterruptRequest.builder().interruptId("gate-" + askCount)
                    .message("please confirm " + TOOL_NAME).build());
        }
    }

    /** 后置交接：首次把工作交给远端，收到远端结果后 reject（合成结果回喂）。 */
    private static final class HandoffRejectRail extends BaseInterruptRail {
        private int handoffCount;
        private Object consumedRemoteResult;

        private HandoffRejectRail() {
            super(List.of(TOOL_NAME));
            setPriority(85);
        }

        @Override
        protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
            if (userInput instanceof RemoteResult remoteResult) {
                consumedRemoteResult = remoteResult.value();
                return reject(remoteResult.value());
            }
            handoffCount++;
            return interrupt(InterruptRequest.builder().interruptId("handoff-" + handoffCount)
                    .message("handing off " + TOOL_NAME).build());
        }
    }

    private record Confirmation(boolean approved) {
    }

    private record RemoteResult(String value) {
    }

    // ────────────────────────── 脚手架 ──────────────────────────

    private static InteractiveInput resumeWith(Object payload) {
        InteractiveInput input = new InteractiveInput();
        input.update(TOOL_CALL_ID, payload);
        return input;
    }

    private void invoke(ReActAgent agent, AgentSessionApi session, Map<String, Object> inputs) {
        try {
            agent.invoke(inputs, session);
        } catch (RuntimeException e) {
            fail("invoke failed: " + e.getMessage(), e);
        }
    }

    private ReActAgent newAgent() {
        ReActAgent agent = new ReActAgent(AgentCard.builder().id(AGENT_ID).name(AGENT_ID)
                .description("settled decision replay test").build());
        agent.configure(ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content", "demo agent")))
                .maxIterations(4).build()
                .configureModelClient(TEST_PROVIDER, "test-key", "mirror://settled-replay", "demo-model", false));
        return agent;
    }

    private Tool createTool() {
        ToolCard card = ToolCard.builder().id(TOOL_NAME).name(TOOL_NAME).description("a guarded tool")
                .inputParams(Map.of("type", "object", "properties", Map.of(), "required", List.of())).build();
        return new LocalFunction(card, (inputs, kwargs) -> {
            toolCallCount.incrementAndGet();
            toolReceivedArgs.set(String.valueOf(inputs));
            return "LOCAL_RESULT";
        });
    }

    private static final class DemoModelFactory implements Model.ModelClientFactory {
        @Override
        public String providerName() {
            return TEST_PROVIDER;
        }

        @Override
        public BaseModelClient create(ModelRequestConfig modelConfig, ModelClientConfig clientConfig) {
            return new DemoModelClient(modelConfig, clientConfig);
        }
    }

    private static final class DemoModelClient extends BaseModelClient {
        private DemoModelClient(ModelRequestConfig modelConfig, ModelClientConfig modelClientConfig) {
            super(modelConfig, modelClientConfig);
        }

        @Override
        public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP, String model,
                Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
                Map<String, Object> kwargs) {
            if (findLastContent(toMessages(messages), "tool") == null) {
                return AssistantMessage.builder().content("").toolCalls(List.of(ToolCall.builder().id(TOOL_CALL_ID)
                        .name(TOOL_NAME).arguments("{}").build())).build();
            }
            return new AssistantMessage("DONE");
        }

        @Override
        public Iterator<AssistantMessageChunk> stream(Object messages, Object tools, Float temperature, Float topP,
                String model, Integer maxTokens, String stop, BaseOutputParser outputParser, Float timeout,
                Map<String, Object> kwargs) {
            return List.<AssistantMessageChunk>of().iterator();
        }

        @Override
        public ImageGenerationResponse generateImage(
                List<com.openjiuwen.core.foundation.llm.schema.UserMessage> messages, String model, String size,
                String negativePrompt, int n, boolean promptExtend, boolean watermark, int seed,
                Map<String, Object> kwargs) {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public AudioGenerationResponse generateSpeech(
                List<com.openjiuwen.core.foundation.llm.schema.UserMessage> messages, String model, String voice,
                String languageType, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public VideoGenerationResponse generateVideo(
                List<com.openjiuwen.core.foundation.llm.schema.UserMessage> messages, String imgUrl, String audioUrl,
                String model, String size, String resolution, int duration, boolean promptExtend, boolean watermark,
                String negativePrompt, Integer seed, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException("not needed");
        }

        private static List<BaseMessage> toMessages(Object messages) {
            List<BaseMessage> result = new ArrayList<>();
            if (messages instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof BaseMessage message) {
                        result.add(message);
                    }
                }
            }
            return result;
        }

        private static String findLastContent(List<BaseMessage> messages, String role) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (role.equals(messages.get(i).getRole())) {
                    return messages.get(i).getContentAsString();
                }
            }
            return null;
        }
    }
}
