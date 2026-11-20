package com.spotifyyoutube.migrator.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RequestCorrelationFilter Tests (Phase 18F)")
class RequestCorrelationFilterTest {

    private RequestCorrelationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        MDC.clear();
        filter = new RequestCorrelationFilter("migrator", "test");
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("Inbound Correlation ID Acceptance & Validation")
    class InboundValidationTests {

        @Test
        @DisplayName("Valid inbound X-Request-ID is accepted and echoed in response")
        void acceptsValidInboundRequestId() throws ServletException, IOException {
            String inboundId = "req-12345-abcde";
            request.addHeader(LoggingConstants.HEADER_REQUEST_ID, inboundId);
            request.setMethod("GET");
            request.setRequestURI("/api/test");

            AtomicReference<String> mdcRequestId = new AtomicReference<>();
            FilterChain chain = (req, res) -> mdcRequestId.set(MDC.get(LoggingConstants.MDC_REQUEST_ID));

            filter.doFilter(request, response, chain);

            assertThat(response.getHeader(LoggingConstants.HEADER_REQUEST_ID)).isEqualTo(inboundId);
            assertThat(response.getHeader(LoggingConstants.HEADER_CORRELATION_ID)).isEqualTo(inboundId);
            assertThat(mdcRequestId.get()).isEqualTo(inboundId);
        }

        @Test
        @DisplayName("Valid inbound X-Correlation-ID is accepted when X-Request-ID is absent")
        void acceptsValidInboundCorrelationId() throws ServletException, IOException {
            String inboundId = "corr-67890-fghij";
            request.addHeader(LoggingConstants.HEADER_CORRELATION_ID, inboundId);
            request.setMethod("POST");
            request.setRequestURI("/api/migrations");

            AtomicReference<String> mdcCorrelationId = new AtomicReference<>();
            FilterChain chain = (req, res) -> mdcCorrelationId.set(MDC.get(LoggingConstants.MDC_CORRELATION_ID));

            filter.doFilter(request, response, chain);

            assertThat(response.getHeader(LoggingConstants.HEADER_REQUEST_ID)).isEqualTo(inboundId);
            assertThat(response.getHeader(LoggingConstants.HEADER_CORRELATION_ID)).isEqualTo(inboundId);
            assertThat(mdcCorrelationId.get()).isEqualTo(inboundId);
        }

        @Test
        @DisplayName("Prefers X-Request-ID when both headers are provided")
        void prefersRequestIdOverCorrelationId() throws ServletException, IOException {
            request.addHeader(LoggingConstants.HEADER_REQUEST_ID, "req-primary-123");
            request.addHeader(LoggingConstants.HEADER_CORRELATION_ID, "corr-secondary-456");

            AtomicReference<String> mdcRequestId = new AtomicReference<>();
            FilterChain chain = (req, res) -> mdcRequestId.set(MDC.get(LoggingConstants.MDC_REQUEST_ID));

            filter.doFilter(request, response, chain);

            assertThat(response.getHeader(LoggingConstants.HEADER_REQUEST_ID)).isEqualTo("req-primary-123");
            assertThat(mdcRequestId.get()).isEqualTo("req-primary-123");
        }

        @Test
        @DisplayName("Missing inbound headers generate a fresh UUIDv4")
        void generatesFreshIdWhenMissing() throws ServletException, IOException {
            AtomicReference<String> mdcRequestId = new AtomicReference<>();
            FilterChain chain = (req, res) -> mdcRequestId.set(MDC.get(LoggingConstants.MDC_REQUEST_ID));

            filter.doFilter(request, response, chain);

            String generatedId = response.getHeader(LoggingConstants.HEADER_REQUEST_ID);
            assertThat(generatedId).isNotBlank();
            assertThat(UUID.fromString(generatedId)).isNotNull();
            assertThat(mdcRequestId.get()).isEqualTo(generatedId);
        }

        @ParameterizedTest(name = "Rejects malformed value: [{0}]")
        @ValueSource(strings = {
                "",
                "   ",
                "invalid id with spaces",
                "req\r\nX-Injected-Header: evil",
                "req\nInjected: evil",
                "id_with_semicolon;drop table",
                "<script>alert(1)</script>",
                "id/with/slashes",
                "id?query=param"
        })
        @DisplayName("Malformed or injected IDs are rejected in favor of fresh UUIDv4")
        void rejectsMalformedOrInjectedIds(String malformedId) throws ServletException, IOException {
            request.addHeader(LoggingConstants.HEADER_REQUEST_ID, malformedId);

            AtomicReference<String> mdcRequestId = new AtomicReference<>();
            FilterChain chain = (req, res) -> mdcRequestId.set(MDC.get(LoggingConstants.MDC_REQUEST_ID));

            filter.doFilter(request, response, chain);

            String responseId = response.getHeader(LoggingConstants.HEADER_REQUEST_ID);
            assertThat(responseId).isNotEqualTo(malformedId);
            assertThat(UUID.fromString(responseId)).isNotNull();
            assertThat(mdcRequestId.get()).isEqualTo(responseId);
        }

        @Test
        @DisplayName("Oversized ID (> 64 chars) is rejected in favor of fresh UUIDv4")
        void rejectsOversizedId() throws ServletException, IOException {
            String oversized = "a".repeat(65);
            request.addHeader(LoggingConstants.HEADER_REQUEST_ID, oversized);

            AtomicReference<String> mdcRequestId = new AtomicReference<>();
            FilterChain chain = (req, res) -> mdcRequestId.set(MDC.get(LoggingConstants.MDC_REQUEST_ID));

            filter.doFilter(request, response, chain);

            String responseId = response.getHeader(LoggingConstants.HEADER_REQUEST_ID);
            assertThat(responseId).isNotEqualTo(oversized);
            assertThat(UUID.fromString(responseId)).isNotNull();
            assertThat(mdcRequestId.get()).isEqualTo(responseId);
        }

        @Test
        @DisplayName("Exact 64 character alphanumeric ID is accepted")
        void acceptsExact64CharId() throws ServletException, IOException {
            String exact64 = "a".repeat(64);
            request.addHeader(LoggingConstants.HEADER_REQUEST_ID, exact64);

            AtomicReference<String> mdcRequestId = new AtomicReference<>();
            FilterChain chain = (req, res) -> mdcRequestId.set(MDC.get(LoggingConstants.MDC_REQUEST_ID));

            filter.doFilter(request, response, chain);

            assertThat(response.getHeader(LoggingConstants.HEADER_REQUEST_ID)).isEqualTo(exact64);
            assertThat(mdcRequestId.get()).isEqualTo(exact64);
        }
    }

    @Nested
    @DisplayName("MDC Context Population & Lifecycle")
    class MdcLifecycleTests {

        @Test
        @DisplayName("MDC contains all standard HTTP context fields during processing")
        void mdcContainsStandardContextFields() throws ServletException, IOException {
            request.addHeader(LoggingConstants.HEADER_REQUEST_ID, "req-test-123");
            request.setMethod("POST");
            request.setRequestURI("/api/migrations/submit");

            AtomicReference<String> appId = new AtomicReference<>();
            AtomicReference<String> envId = new AtomicReference<>();
            AtomicReference<String> methodId = new AtomicReference<>();
            AtomicReference<String> routeId = new AtomicReference<>();

            FilterChain chain = (req, res) -> {
                appId.set(MDC.get(LoggingConstants.MDC_APPLICATION));
                envId.set(MDC.get(LoggingConstants.MDC_ENVIRONMENT));
                methodId.set(MDC.get(LoggingConstants.MDC_HTTP_METHOD));
                routeId.set(MDC.get(LoggingConstants.MDC_HTTP_ROUTE));
            };

            filter.doFilter(request, response, chain);

            assertThat(appId.get()).isEqualTo("migrator");
            assertThat(envId.get()).isEqualTo("test");
            assertThat(methodId.get()).isEqualTo("POST");
            assertThat(routeId.get()).isEqualTo("/api/migrations/submit");
        }

        @Test
        @DisplayName("MDC is guaranteed cleared after request completes")
        void mdcIsClearedAfterRequest() throws ServletException, IOException {
            request.addHeader(LoggingConstants.HEADER_REQUEST_ID, "req-cleanup-test");
            FilterChain chain = (req, res) -> assertThat(MDC.get(LoggingConstants.MDC_REQUEST_ID)).isEqualTo("req-cleanup-test");

            filter.doFilter(request, response, chain);

            assertThat(MDC.get(LoggingConstants.MDC_REQUEST_ID)).isNull();
            assertThat(MDC.get(LoggingConstants.MDC_APPLICATION)).isNull();
            assertThat(MDC.getCopyOfContextMap()).isNull();
        }

        @Test
        @DisplayName("MDC is guaranteed restored/cleared even when filter chain throws an exception")
        void mdcIsClearedWhenExceptionThrown() {
            request.addHeader(LoggingConstants.HEADER_REQUEST_ID, "req-fail-test");
            FilterChain chain = (req, res) -> {
                assertThat(MDC.get(LoggingConstants.MDC_REQUEST_ID)).isEqualTo("req-fail-test");
                throw new ServletException("Simulated filter failure");
            };

            assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                    .isInstanceOf(ServletException.class)
                    .hasMessage("Simulated filter failure");

            assertThat(MDC.get(LoggingConstants.MDC_REQUEST_ID)).isNull();
            assertThat(MDC.getCopyOfContextMap()).isNull();
        }

        @Test
        @DisplayName("Preserves existing pre-request MDC context if nested filter invocation occurs")
        void preservesPreExistingContextOnNesting() throws ServletException, IOException {
            MDC.put("outerKey", "outerValue");

            request.addHeader(LoggingConstants.HEADER_REQUEST_ID, "nested-req-123");
            FilterChain chain = (req, res) -> {
                assertThat(MDC.get(LoggingConstants.MDC_REQUEST_ID)).isEqualTo("nested-req-123");
                assertThat(MDC.get("outerKey")).isEqualTo("outerValue");
            };

            filter.doFilter(request, response, chain);

            assertThat(MDC.get(LoggingConstants.MDC_REQUEST_ID)).isNull();
            assertThat(MDC.get("outerKey")).isEqualTo("outerValue");
        }

        @Test
        @DisplayName("Sequential requests executed on the same thread do not leak context across requests")
        void threadReuseDoesNotLeakContext() throws ServletException, IOException {
            for (int i = 1; i <= 5; i++) {
                MockHttpServletRequest req = new MockHttpServletRequest();
                MockHttpServletResponse res = new MockHttpServletResponse();
                String reqId = "reused-thread-req-" + i;
                req.addHeader(LoggingConstants.HEADER_REQUEST_ID, reqId);

                final int iteration = i;
                AtomicBoolean ran = new AtomicBoolean(false);
                FilterChain chain = (r, s) -> {
                    assertThat(MDC.get(LoggingConstants.MDC_REQUEST_ID)).isEqualTo(reqId);
                    // Ensure no stale context from previous iterations
                    if (iteration > 1) {
                        assertThat(MDC.get("customPropIteration" + (iteration - 1))).isNull();
                    }
                    MDC.put("customPropIteration" + iteration, "val" + iteration);
                    ran.set(true);
                };

                filter.doFilter(req, res, chain);

                assertThat(ran.get()).isTrue();
                assertThat(MDC.get(LoggingConstants.MDC_REQUEST_ID)).isNull();
                assertThat(MDC.getCopyOfContextMap()).isNull();
            }
        }
    }
}
