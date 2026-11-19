package com.spotifyyoutube.migrator.youtube.infrastructure;

import com.spotifyyoutube.migrator.common.metrics.MetricNames;
import com.spotifyyoutube.migrator.common.metrics.MetricsRegistrationHelper;
import com.spotifyyoutube.migrator.common.metrics.TagKeys;
import com.spotifyyoutube.migrator.common.metrics.TagPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;

/**
 * Outbound HTTP metrics interceptor for YouTube Data API requests.
 *
 * <p>Instruments request volume, error counts, and latency timers at the HTTP transport boundary.
 * Enforces strictly bounded low-cardinality tags via {@link TagPolicy}.
 */
@Component
public class YouTubeMetricsInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(YouTubeMetricsInterceptor.class);
    private static final String PROVIDER = "youtube";

    private final MeterRegistry registry;

    @Autowired
    public YouTubeMetricsInterceptor(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry must not be null");
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        long startNanos = System.nanoTime();
        String operation = resolveOperation(request);
        ClientHttpResponse response = null;
        Exception error = null;

        try {
            response = execution.execute(request, body);
            return response;
        } catch (IOException | RuntimeException ex) {
            error = ex;
            throw ex;
        } finally {
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            recordMetrics(operation, response, error, duration);
        }
    }

    private void recordMetrics(String operation, ClientHttpResponse response, Exception error, Duration duration) {
        try {
            String status;
            String timerStatus;
            boolean isError = false;
            int statusCode = 0;
            String retryAfter = null;

            if (error != null) {
                status = "error";
                timerStatus = "error";
                isError = true;
            } else if (response != null) {
                try {
                    HttpStatusCode httpStatus = response.getStatusCode();
                    statusCode = httpStatus.value();
                    status = String.valueOf(statusCode);
                    isError = httpStatus.isError();
                    timerStatus = isError ? "error" : "success";
                    retryAfter = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                } catch (IOException ioEx) {
                    status = "error";
                    timerStatus = "error";
                    isError = true;
                }
            } else {
                status = "unknown";
                timerStatus = "error";
                isError = true;
            }

            // 1. Record Request Counter (every outbound request attempt)
            Tags requestTags = TagPolicy.tags(
                    TagKeys.PROVIDER, PROVIDER,
                    TagKeys.OPERATION, operation,
                    TagKeys.STATUS, status
            );
            Counter requestCounter = MetricsRegistrationHelper.counter(
                    registry,
                    MetricNames.YOUTUBE_API_REQUESTS,
                    "Total number of outbound YouTube API requests",
                    requestTags
            );
            requestCounter.increment();

            // 2. Record Error Counter (if status >= 400 or exception occurred)
            if (isError) {
                String errorCategory = classifyErrorCategory(statusCode, retryAfter, error);
                Tags errorTags = TagPolicy.tags(
                        TagKeys.PROVIDER, PROVIDER,
                        TagKeys.OPERATION, operation,
                        TagKeys.STATUS, status,
                        TagKeys.ERROR_CATEGORY, errorCategory
                );
                Counter errorCounter = MetricsRegistrationHelper.counter(
                        registry,
                        MetricNames.YOUTUBE_API_ERRORS,
                        "Total number of outbound YouTube API errors encountered",
                        errorTags
                );
                errorCounter.increment();
            }

            // 3. Record Latency Timer
            Duration safeDuration = duration.isNegative() ? Duration.ZERO : duration;
            Tags timerTags = TagPolicy.tags(
                    TagKeys.PROVIDER, PROVIDER,
                    TagKeys.OPERATION, operation,
                    TagKeys.STATUS, timerStatus
            );
            Timer timer = MetricsRegistrationHelper.timer(
                    registry,
                    MetricNames.YOUTUBE_API_LATENCY,
                    "Total duration of outbound YouTube API requests",
                    timerTags
            );
            timer.record(safeDuration);

        } catch (Exception ex) {
            log.warn("Failed to record YouTube API metrics: {}", ex.getMessage());
        }
    }

    /**
     * Resolves an outbound request to a bounded low-cardinality operation identifier.
     *
     * @param request outbound HTTP request
     * @return bounded operation name
     */
    public static String resolveOperation(HttpRequest request) {
        if (request == null || request.getURI() == null) {
            return "other";
        }
        String path = request.getURI().getPath();
        if (path == null) {
            return "other";
        }
        HttpMethod method = request.getMethod();
        String query = request.getURI().getQuery();

        if (path.endsWith("/search")) {
            return "search";
        }
        if (path.endsWith("/videos")) {
            return "get_video";
        }
        if (path.endsWith("/playlists")) {
            if (HttpMethod.POST.equals(method)) {
                return "create_playlist";
            }
            if (query != null && query.contains("mine=true")) {
                return "get_my_playlists";
            }
            return "get_playlist";
        }
        if (path.endsWith("/playlistItems")) {
            if (HttpMethod.POST.equals(method)) {
                return "add_playlist_item";
            }
            if (HttpMethod.DELETE.equals(method)) {
                return "remove_playlist_item";
            }
            return "get_playlist_items";
        }
        return "other";
    }

    /**
     * Classifies response status code, retry-after header, or exception into a bounded error category.
     *
     * @param statusCode HTTP status code (0 if exception)
     * @param retryAfter optional Retry-After header
     * @param ex         optional exception thrown during dispatch
     * @return bounded error category
     */
    public static String classifyErrorCategory(int statusCode, String retryAfter, Exception ex) {
        if (ex != null) {
            if (ex instanceof SocketTimeoutException
                    || (ex.getCause() != null && ex.getCause() instanceof SocketTimeoutException)) {
                return "timeout";
            }
            return "network_error";
        }
        if (statusCode == 429 || (retryAfter != null && !retryAfter.isBlank())) {
            return "rate_limit";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "auth_error";
        }
        if (statusCode == 400 || statusCode == 422) {
            return "validation_error";
        }
        if (statusCode == 404) {
            return "client_error";
        }
        if (statusCode >= 400 && statusCode < 500) {
            return "client_error";
        }
        if (statusCode >= 500) {
            return "server_error";
        }
        return "unknown_error";
    }
}
