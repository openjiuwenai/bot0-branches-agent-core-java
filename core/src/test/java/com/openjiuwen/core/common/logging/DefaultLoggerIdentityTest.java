/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.core.common.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.common.logging.defaults.DefaultLogger;
import com.openjiuwen.core.common.logging.events.BaseLogEvent;
import com.openjiuwen.core.common.logging.events.LogEventType;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class DefaultLoggerIdentityTest {
    private static final String FIRST_TRACE = "0123456789abcdef0123456789abcdef";
    private static final String SECOND_TRACE = "fedcba9876543210fedcba9876543210";
    private static final ObjectMapper JSON = new ObjectMapper();

    private String originalSessionId;
    private Map<String, String> originalMdc;

    @BeforeEach
    void saveContext() {
        originalSessionId = LoggingUtils.getSessionId();
        originalMdc = MDC.getCopyOfContextMap();
    }

    @AfterEach
    void restoreContext() {
        LoggingUtils.setSessionId(originalSessionId);
        if (originalMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(originalMdc);
        }
    }

    @Test
    void usesDifferentTraceIdsForTheSameSession() throws Exception {
        LoggingUtils.setSessionId("shared-session");
        DefaultLogger logger = new DefaultLogger("event-identity", Map.of());
        try (Slf4jLogCapture capture = new Slf4jLogCapture("event-identity")) {
            try (Scope ignored = trace(FIRST_TRACE).makeCurrent()) {
                logger.logEvent("first", LogEventType.AGENT_START, null);
            }
            try (Scope ignored = trace(SECOND_TRACE).makeCurrent()) {
                logger.logEvent("second", LogEventType.AGENT_START, null);
            }

            assertEquals(2, capture.formattedMessages().size());
            assertIdentity(JSON.readTree(capture.formattedMessages().get(0)), "shared-session", FIRST_TRACE);
            assertIdentity(JSON.readTree(capture.formattedMessages().get(1)), "shared-session", SECOND_TRACE);
        }
    }

    @Test
    void preservesExplicitEventIdentity() throws Exception {
        LoggingUtils.setSessionId("ambient-session");
        DefaultLogger logger = new DefaultLogger("explicit-event-identity", Map.of());
        BaseLogEvent event = new BaseLogEvent();
        event.setSessionId("explicit-session");
        event.setTraceId(SECOND_TRACE);
        try (Slf4jLogCapture capture = new Slf4jLogCapture("explicit-event-identity");
                Scope ignored = trace(FIRST_TRACE).makeCurrent()) {
            logger.logEvent("message", LogEventType.AGENT_START, event);

            assertIdentity(JSON.readTree(capture.formattedMessages().get(0)), "explicit-session", SECOND_TRACE);
        }
    }

    @Test
    void omitsTraceWhenThereIsNoValidContextAndOmitsDefaultSession() throws Exception {
        DefaultLogger logger = new DefaultLogger("no-trace-identity", Map.of());
        try (Slf4jLogCapture capture = new Slf4jLogCapture("no-trace-identity");
                Scope ignored = Context.root().makeCurrent()) {
            LoggingUtils.setSessionId("session-without-span");
            logger.logEvent("session only", LogEventType.AGENT_START, null);
            LoggingUtils.setSessionId();
            logger.logEvent("neither", LogEventType.AGENT_START, null);

            JsonNode sessionOnly = JSON.readTree(capture.formattedMessages().get(0));
            assertEquals("session-without-span", sessionOnly.path("session_id").asText());
            assertFalse(sessionOnly.has("trace_id"));
            assertFalse(sessionOnly.at("/metadata/_log_context").has("trace_id"));
            JsonNode neither = JSON.readTree(capture.formattedMessages().get(1));
            assertFalse(neither.has("session_id"));
            assertFalse(neither.has("trace_id"));
        }
    }

    @Test
    void usesOnlyTheActiveTraceIdInPlainLogMdc() {
        LoggingUtils.setSessionId("plain-log-session");
        Logger backend = mock(Logger.class);
        when(backend.isInfoEnabled()).thenReturn(true);
        doAnswer(call -> {
            if ("with trace".equals(call.getArgument(0))) {
                assertEquals(FIRST_TRACE, MDC.get("trace_id"));
            } else {
                assertNull(MDC.get("trace_id"));
            }
            return null;
        }).when(backend).info(anyString(), any(Object[].class));
        try (MockedStatic<LoggerFactory> factory = mockStatic(LoggerFactory.class)) {
            factory.when(() -> LoggerFactory.getLogger("plain-log-identity")).thenReturn(backend);
            DefaultLogger logger = new DefaultLogger("plain-log-identity", Map.of());

            try (Scope ignored = trace(FIRST_TRACE).makeCurrent()) {
                logger.info("with trace");
            }
            try (Scope ignored = Context.root().makeCurrent()) {
                logger.info("without trace");
            }
            verify(backend, times(2)).info(anyString(), any(Object[].class));
        }
    }

    private static Span trace(String traceId) {
        return Span.wrap(SpanContext.create(traceId, "0123456789abcdef",
                TraceFlags.getSampled(), TraceState.getDefault()));
    }

    private static void assertIdentity(JsonNode entry, String sessionId, String traceId) {
        assertEquals(sessionId, entry.path("session_id").asText());
        assertEquals(traceId, entry.path("trace_id").asText());
        assertEquals(sessionId, entry.at("/metadata/_log_context/session_id").asText());
        assertEquals(traceId, entry.at("/metadata/_log_context/trace_id").asText());
    }
}
