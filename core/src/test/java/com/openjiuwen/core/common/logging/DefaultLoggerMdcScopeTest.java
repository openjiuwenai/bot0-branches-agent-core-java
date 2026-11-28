/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.core.common.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.common.logging.defaults.DefaultLogger;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class DefaultLoggerMdcScopeTest {
    private static final Map<String, String> HOST_CONTEXT = Map.of(
            "trace_id", "host-trace", "member_id", "host-member",
            "log_type", "host-log", "request_id", "host-request");

    private Map<String, String> originalContext;

    @BeforeEach
    void saveContext() {
        originalContext = MDC.getCopyOfContextMap();
    }

    @AfterEach
    void restoreContext() {
        if (originalContext == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(originalContext);
        }
    }

    @Test
    void restoresOuterAndHostContextAfterNestedLogging() {
        MDC.setContextMap(HOST_CONTEXT);
        Logger outerBackend = mock(Logger.class);
        Logger innerBackend = mock(Logger.class);
        when(outerBackend.isInfoEnabled()).thenReturn(true);
        when(innerBackend.isInfoEnabled()).thenReturn(true);

        try (MockedStatic<LoggerFactory> factory = mockStatic(LoggerFactory.class)) {
            factory.when(() -> LoggerFactory.getLogger("outer-mdc")).thenReturn(outerBackend);
            factory.when(() -> LoggerFactory.getLogger("inner-mdc")).thenReturn(innerBackend);
            DefaultLogger outer = new DefaultLogger("outer-mdc", Map.of());
            DefaultLogger inner = new DefaultLogger("inner-mdc", Map.of());

            doAnswer(call -> {
                assertEquals("inner-mdc", MDC.get("log_type"));
                assertEquals("host-request", MDC.get("request_id"));
                return null;
            }).when(innerBackend).info(eq("inner"), any(Object[].class));

            doAnswer(call -> {
                assertEquals("outer-mdc", MDC.get("log_type"));
                inner.info("inner");
                assertEquals("outer-mdc", MDC.get("log_type"));
                return null;
            }).when(outerBackend).info(eq("outer"), any(Object[].class));

            outer.info("outer");
            verify(innerBackend).info(eq("inner"), any(Object[].class));
            assertEquals(HOST_CONTEXT, MDC.getCopyOfContextMap());
        }
    }

    @Test
    void restoresHostContextWhenBackendThrows() {
        MDC.setContextMap(HOST_CONTEXT);
        Logger backend = mock(Logger.class);
        when(backend.isInfoEnabled()).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("backend failed");
        doThrow(failure).when(backend).info(eq("message"), any(Object[].class));

        try (MockedStatic<LoggerFactory> factory = mockStatic(LoggerFactory.class)) {
            factory.when(() -> LoggerFactory.getLogger("throwing-mdc")).thenReturn(backend);
            DefaultLogger logger = new DefaultLogger("throwing-mdc", Map.of());

            assertSame(failure, assertThrows(IllegalStateException.class, () -> logger.info("message")));
            assertEquals(HOST_CONTEXT, MDC.getCopyOfContextMap());
        }
    }

    @Test
    void clearsTemporaryValuesWhenHostContextIsEmpty() {
        MDC.clear();
        DefaultLogger logger = new DefaultLogger("empty-mdc", Map.of());
        try (Slf4jLogCapture capture = new Slf4jLogCapture("empty-mdc")) {
            logger.info("message");
            assertEquals(1, capture.formattedMessages().size());
        }

        assertNull(MDC.get("trace_id"));
        assertNull(MDC.get("member_id"));
        assertNull(MDC.get("log_type"));
    }
}
