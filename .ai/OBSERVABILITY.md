# Observability Operations Guide & Runbook (Phase 18G)

## 1. Observability Architecture Overview

The Spotify–YouTube Migrator observability architecture implements end-to-end monitoring, metrics collection, structured logging, and alerting across migration and provider workflows.

```
+-----------------------------------------------------------------------------------------+
|                                    Spring Boot Backend                                  |
|                                                                                         |
|  +--------------------+   +-----------------------+   +------------------------------+  |
|  |  Migration Service |   | Matching Engine       |   | External Provider Interceptors| |
|  |  (Lifecycle & Run) |   | (Score & Confidence)  |   | (Spotify Web API / YouTube)  |  |
|  +---------+----------+   +-----------+-----------+   +--------------+---------------+  |
|            |                          |                              |                  |
|            v                          v                              v                  |
|       MigrationMetrics         MigrationMetrics            Interceptors                 |
|            \                          |                             /                   |
|             \                         |                            /                    |
|              +------------------------+---------------------------+                     |
|                                       v                                                 |
|                         Micrometer MeterRegistry                                        |
|                          (Common Tag: application)                                      |
|                                       v                                                 |
|                         PrometheusMeterRegistry                                         |
|                                       v                                                 |
|                   Spring Boot Actuator: /actuator/prometheus                            |
+---------------------------------------+-------------------------------------------------+
                                        ^
                                        | HTTP Scrape (10s)
                                        |
+---------------------------------------+-------------------------------------------------+
|                               Observability Infrastructure                              |
|                                                                                         |
|  +-------------------------+                     +----------------------------------+   |
|  |       Prometheus        |                     |             Grafana              |   |
|  |     (Port: 9090)        +-------------------->|           (Port: 3001)           |   |
|  |  - Scrape Config        |  PromQL Datasource  |  - Migration Overview            |   |
|  |  - Alert Rules          |                     |  - Matching Quality              |   |
|  +-------------------------+                     |  - Provider Health               |   |
+-----------------------------------------------------------------------------------------+
```

---

## 2. Metrics Inventory & Exported Prometheus Names

All metric names follow the Micrometer dot-separated convention in source code and are exported to Prometheus in snake_case format with appropriate type suffixes (e.g. `_total`, `_seconds`).

| Micrometer Metric Name | Metric Type | Prometheus Exported Name(s) | Description |
|:---|:---|:---|:---|
| `migration.started` | Counter | `migration_started_total` | Total migration jobs initiated |
| `migration.completed` | Counter | `migration_completed_total` | Total migration jobs successfully completed |
| `migration.failed` | Counter | `migration_failed_total` | Total migration jobs that failed |
| `migration.duration` | Timer | `migration_duration_seconds_count`<br>`migration_duration_seconds_sum`<br>`migration_duration_seconds_max` | Duration of migration runs from start to terminal outcome |
| `tracks.matched` | Counter | `tracks_matched_total` | Total tracks successfully matched to YouTube candidates |
| `tracks.unmatched` | Counter | `tracks_unmatched_total` | Total tracks that could not be matched |
| `matching.score` | DistributionSummary | `matching_score_count`<br>`matching_score_sum`<br>`matching_score_max` | Statistical distribution of track match confidence scores (0.0 to 1.0) |
| `spotify.api.requests` | Counter | `spotify_api_requests_total` | Outbound Spotify Web API request volume |
| `spotify.api.errors` | Counter | `spotify_api_errors_total` | Outbound Spotify Web API error volume |
| `spotify.api.latency` | Timer | `spotify_api_latency_seconds_count`<br>`spotify_api_latency_seconds_sum`<br>`spotify_api_latency_seconds_max` | Outbound Spotify API request latency |
| `youtube.api.requests` | Counter | `youtube_api_requests_total` | Outbound YouTube Data API request volume |
| `youtube.api.errors` | Counter | `youtube_api_errors_total` | Outbound YouTube Data API error volume |
| `youtube.api.latency` | Timer | `youtube_api_latency_seconds_count`<br>`youtube_api_latency_seconds_sum`<br>`youtube_api_latency_seconds_max` | Outbound YouTube API request latency |

---

## 3. Label / Tag Inventory & Cardinality Policy

All metrics enforce low-cardinality label rules via `TagPolicy` and `TagKeys`. Prohibited high-cardinality keys (such as `user_id`, `playlist_id`, `track_id`, `correlation_id`, `execution_id`, exception messages, and URLs) are rejected at registration time.

| Tag Key | Permitted / Bounded Values | Used On Metrics |
|:---|:---|:---|
| `application` | `spotify-youtube-migrator` (common static tag) | All metrics |
| `mode` | `manual`, `automated` | `migration.started`, `migration.completed`, `migration.duration` |
| `outcome` | `completed`, `partially_completed`, `failed` | `migration.completed`, `migration.failed`, `migration.duration` |
| `error_category` | `rate_limit`, `quota_exceeded`, `network_error`, `network_timeout`, `auth_error`, `validation_error`, `client_error`, `server_error`, `unknown_error` | `migration.failed`, `spotify.api.errors`, `youtube.api.errors` |
| `decision` | `matched`, `unmatched`, `review_required`, `no_match` | `tracks.matched`, `tracks.unmatched`, `matching.score` |
| `confidence` | `excellent`, `good`, `poor`, `high`, `medium`, `low` | `tracks.matched`, `matching.score` |
| `provider` | `spotify`, `youtube` | `spotify.api.*`, `youtube.api.*` |
| `operation` | `get_playlist`, `get_playlist_tracks`, `get_user_profile`, `get_user_playlists`, `search`, `create_playlist`, `insert_playlist_item`, `other` | `spotify.api.*`, `youtube.api.*` |
| `status` | HTTP status code (`200`, `401`, `403`, `429`, `500`), `error`, `unknown`, `success` | `spotify.api.*`, `youtube.api.*` |

---

## 4. Prometheus Endpoint & Security Policy

### Endpoint Exposure
- **Exposed Management Endpoint:** `GET /actuator/prometheus`
- **Health Probes:** `GET /actuator/health`, `GET /actuator/health/liveness`, `GET /actuator/health/readiness` (`show-details: never`)
- **Info:** `GET /actuator/info`

### Strict Lockdown
- **Prohibited Endpoints (403 Forbidden):**
  - `/actuator/metrics` (raw Micrometer JSON metrics API)
  - `/actuator/env`
  - `/actuator/beans`
  - `/actuator/heapdump`
  - `/actuator/threaddump`
  - `/actuator/configprops`
  - `/actuator/loggers`
  - `/actuator/mappings`
- Non-GET methods (POST, PUT, DELETE) on `/actuator/**` are rejected with HTTP 403 Forbidden.

### Production Network Boundary Assumptions
- `/actuator/prometheus` provides metric aggregation data. In a production environment, this endpoint should **not** be exposed directly to public internet traffic.
- Access must be restricted via network-level firewall, VPC ingress security groups, or reverse proxy rules (e.g. Nginx, Envoy, or AWS ALB) allowing traffic exclusively from the Prometheus scraper IP address.

---

## 5. How to Run Prometheus Locally

Prometheus is integrated into the project's root `docker-compose.yml`.

### Start Prometheus
```powershell
docker compose up -d prometheus
```

### Access Prometheus Web UI
Open your browser to:
[http://localhost:9090](http://localhost:9090)

### Check Scrape Target Status
Navigate to **Status -> Targets** in the Prometheus UI:
[http://localhost:9090/targets](http://localhost:9090/targets)
Verify that the `spotify-youtube-migrator` endpoint shows status `UP`.

---

## 6. How to Run Grafana Locally

### Start Grafana
```powershell
docker compose up -d grafana
```
*(Prometheus will be started automatically if not already running due to `depends_on`).*

### Start Full Observability Stack (Postgres + Prometheus + Grafana)
```powershell
docker compose up -d
```

### Access Grafana
- URL: [http://localhost:3001](http://localhost:3001)
- Default Username: `admin`
- Default Password: `admin` (configurable via `GRAFANA_ADMIN_PASSWORD` env var)

---

## 7. Grafana Provisioning & Dashboards

Grafana dashboards and datasource configurations are automatically provisioned from version-controlled files on startup:

- **Datasource Provisioning:** `observability/grafana/provisioning/datasources/datasource.yml`
  - Configures the default Prometheus datasource pointing to `http://prometheus:9090`.
- **Dashboard Provider:** `observability/grafana/provisioning/dashboards/dashboard-provider.yml`
  - Automatically loads JSON definitions from `observability/grafana/dashboards/` into folder `Spotify-YouTube Migrator`.

### Available Dashboards:
1. **Migration Overview (`migrator-migration-overview`):**
   - Single-stat KPIs: Total Started, Completed, Failed, and Success Rate (%).
   - Timeseries: Migration throughput (ops/sec), failure breakdown by error category.
   - Timeseries & Pie Chart: Migration duration (mean & max), completed migrations by outcome.
2. **Matching Quality & Performance (`migrator-matching-quality`):**
   - Single-stat KPIs: Total Matched, Unmatched, Track Match Ratio (%), Average Match Score.
   - Timeseries: Track matching rates, matched tracks grouped by confidence tier.
   - Timeseries & Pie Chart: Rolling average match score trend, decision breakdown.
3. **External Provider Health (`migrator-provider-health`):**
   - Single-stat KPIs: Spotify & YouTube total requests, error rates (%).
   - Timeseries: Outbound API request rates, error rates by error category.
   - Latencies: Provider average latency and max latency.
   - Operations: Operation-level request rates for Spotify and YouTube.

---

## 8. How to Verify Scrape Target Health

From PowerShell:

### 1. Check Backend Actuator Scrape Output
```powershell
Invoke-WebRequest -Uri "http://localhost:8080/actuator/prometheus" -UseBasicParsing | Select-Object -ExpandProperty Content | Select-String "application=\"spotify-youtube-migrator\""
```

### 2. Check Prometheus Target Health via API
```powershell
Invoke-RestMethod -Uri "http://localhost:9090/api/v1/targets" | ConvertTo-Json -Depth 4
```

### 3. Verify Target `up` Metric in Prometheus
```powershell
Invoke-RestMethod -Uri "http://localhost:9090/api/v1/query?query=up{job='spotify-youtube-migrator'}" | ConvertTo-Json -Depth 4
```

---

## 9. How to Inspect Metrics Manually

### Via PowerShell Web Request
```powershell
# Fetch raw Prometheus text output
(Invoke-WebRequest -Uri "http://localhost:8080/actuator/prometheus" -UseBasicParsing).Content

# Grep for migration metrics
(Invoke-WebRequest -Uri "http://localhost:8080/actuator/prometheus" -UseBasicParsing).Content -split "`n" | Select-String "migration_"

# Grep for provider metrics
(Invoke-WebRequest -Uri "http://localhost:8080/actuator/prometheus" -UseBasicParsing).Content -split "`n" | Select-String "api_"
```

---

## 10. Alert Rules & Threshold Rationale

Defined in `observability/prometheus/alerts.yml`:

| Alert Name | Severity | Expression | Threshold Rationale |
|:---|:---|:---|:---|
| `MigrationHighFailureRate` | `critical` | `(sum(rate(migration_failed_total[15m])) / (sum(rate(migration_started_total[15m])) > 0)) * 100 > 15` for 5m | Sustained failure rate > 15% indicates significant upstream provider outage or systemic worker fault. Evaluated over 15m to avoid transient anomalies. |
| `MigrationElevatedFailures` | `warning` | `sum(increase(migration_failed_total[10m])) > 5` for 3m | Warns when 5+ migrations fail in a 10m window, allowing operators to intervene before customer impact spreads. |
| `MigrationHighDuration` | `warning` | `(sum(rate(migration_duration_seconds_sum[15m])) / (sum(rate(migration_duration_seconds_count[15m])) > 0)) > 60` for 5m | Average migration duration exceeding 60s suggests provider rate-limiting delay or huge playlists stalling workers. |
| `YouTubeQuotaExceeded` | `critical` | `sum(increase(youtube_api_errors_total{error_category="quota_exceeded"}[5m])) > 0` for 1m | Fires immediately upon detecting YouTube Data API daily 10,000 unit quota exhaustion. Requires operator attention. |
| `YouTubeRateLimitSpike` | `warning` | `sum(rate(youtube_api_errors_total{error_category="rate_limit"}[5m])) > 2` for 3m | Fires when YouTube API returns HTTP 429 rate limit responses at >2 req/s, signaling worker concurrency throttling is required. |
| `SpotifyApiElevatedErrorRate` | `warning` | `(sum(rate(spotify_api_errors_total[5m])) / (sum(rate(spotify_api_requests_total[5m])) > 0)) * 100 > 10` for 5m | Spotify API error rate >10% over 5m indicates degraded Spotify services or token refresh issues. |
| `YouTubeApiHighLatency` | `warning` | `(sum(rate(youtube_api_latency_seconds_sum[5m])) / (sum(rate(youtube_api_latency_seconds_count[5m])) > 0)) > 3` for 5m | Average YouTube request taking >3 seconds indicates API degradation or network throttling. |
| `SpotifyYouTubeMigratorTargetDown` | `critical` | `up{job="spotify-youtube-migrator"} == 0` for 2m | Backend process crashed or is unreachable from scraper. |

---

## 11. Alert Validation Instructions

Validate alert rules syntax using Prometheus `promtool`:
```powershell
docker run --rm -v "C:\Projects\spotify-youtube-migrator\observability\prometheus:/etc/prometheus" --entrypoint promtool prom/prometheus:v2.54.1 check rules /etc/prometheus/alerts.yml
```

---

## 12. Common Troubleshooting Scenarios

### Scenario 1: Prometheus Scrape Target is `DOWN`
1. Check if backend is running on `http://localhost:8080`.
2. Check `http://localhost:8080/actuator/health` to confirm Spring Boot is up.
3. On Docker Desktop for Windows, ensure `host.docker.internal` resolves correctly. If not, inspect `extra_hosts` in `docker-compose.yml`.

### Scenario 2: Grafana Displays "No Data"
1. Check Prometheus datasource connection in Grafana (**Configuration -> Data Sources -> Prometheus -> Save & Test**).
2. Check that the Prometheus URL is `http://prometheus:9090`.
3. If no migrations have run yet, counters will be absent from Prometheus until first increment. Queries use `or vector(0)` on stat cards to show `0` cleanly.

### Scenario 3: 403 Forbidden When Scraping `/actuator/prometheus`
1. Verify `SecurityConfig.java` has `.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()`.
2. Verify `application.yml` has `management.endpoints.web.exposure.include` containing `prometheus`.

---

## 13. Security Limitations & Production Deployment Warnings

> [!WARNING]
> **Production Boundary Considerations:**
> 1. In production, never expose `/actuator/prometheus` to the public internet. Ensure reverse proxy (e.g. AWS ALB, Nginx, Cloudflare) drops or rejects `/actuator/**` requests from external clients.
> 2. Metrics do not contain sensitive tokens, user passwords, or personal names, but do reveal aggregate request counts and timing characteristics.
> 3. Grafana default credentials (`admin:admin`) should be updated immediately in production environments using the `GF_SECURITY_ADMIN_PASSWORD` environment variable.

---

## 14. Shutdown & Cleanup Instructions

To gracefully stop and remove observability containers without deleting persistent database data:
```powershell
# Stop Prometheus and Grafana containers
docker compose stop prometheus grafana

# Or remove containers while preserving volumes
docker compose down

# If you wish to wipe observability data completely
docker compose down -v
```

---

## 15. Formal Observability Milestone Freeze (Phase 18H)

- **Freeze Date:** 2026-11-20
- **Milestone Status:** **FROZEN — ALL GATES PASSED**
- **Verification Baseline Commit:** `1c0f898dca3b9044121f6dd360d23d2d3bbfe88a` (`1c0f898`)
- **Phases Covered:**
  - **Phase 18A**: Observability Architecture Audit & Metric Strategy (`cda57ce`)
  - **Phase 18B**: Application Health & Spring Actuator Probe Hardening (`22a974c`)
  - **Phase 18C**: Micrometer Metrics Foundation & Cardinality Policy (`5ab97ff`)
  - **Phase 18D**: Migration & Matching Business Metrics (`00a3c06`)
  - **Phase 18E**: External Provider Metrics for Spotify & YouTube (`1e2861d`)
  - **Phase 18F**: Structured JSON Logging, MDC Context & Correlation IDs (`0c4fdc1`)
  - **Phase 18G**: Prometheus Exposure, Grafana Dashboards, Alerting & Operational Setup (`1c0f898`)
  - **Phase 18H**: Final End-to-End Regression, Audit & Formal Milestone Freeze
- **Actual Verification Results:**
  - **Full Backend Regression Suite:** **2,683 tests run, 0 failures, 0 errors, 0 skipped** (`mvn test`, total time: 02:13 min).
  - **Checkstyle Compliance:** **0 violations** (`mvn checkstyle:check`).
  - **Prometheus Validation:** `promtool check config` (SUCCESS: 1 rule file found, valid syntax), `promtool check rules` (SUCCESS: 8 rules found, valid PromQL syntax).
  - **Docker Compose Configuration:** `docker compose config` (SUCCESS: clean exit 0).
  - **Runtime Verification:** Live container startup, Prometheus target discovery at `host.docker.internal:8080/actuator/prometheus`, Grafana datasource provisioning at `http://prometheus:9090`, and 3 dashboards auto-provisioned into folder `Spotify-YouTube Migrator`.
- **Security Audit Summary:**
  - Minimal management endpoint exposure: only `GET /actuator/prometheus`, `GET /actuator/health` (with probes), and `GET /actuator/info` are permitted.
  - Strict lockdown: `/actuator/metrics`, `/actuator/env`, `/actuator/beans`, `/actuator/heapdump`, `/actuator/threaddump`, `/actuator/configprops`, `/actuator/loggers`, and `/actuator/mappings` return HTTP `403 Forbidden`.
  - Health probes enforce `show-details: never` concealing internal database and disk diagnostics.
  - Non-GET requests on `/actuator/**` are rejected with HTTP `403 Forbidden`.
  - Zero high-cardinality keys, UUIDs, URLs, emails, or user data in metric tags (`TagPolicy` enforced).
  - Production structured JSON logging with automated credential/token/cookie redaction (`SensitiveDataSanitizer`).
- **Known Limitations & Operational Caveats:**
  - In production, `/actuator/prometheus` must be isolated behind an internal network boundary or reverse proxy (AWS ALB, Nginx).
  - Grafana admin credentials (`admin:admin`) must be changed using `GF_SECURITY_ADMIN_PASSWORD` in production.
- **Explicit Statement:**
  > [!IMPORTANT]
  > **MILESTONE FREEZE:** Phase 18 (Observability) is formally **FROZEN**. All planned capabilities across health, metrics, logging, correlation, Prometheus scraping, Grafana dashboards, and alerting are complete, fully tested, documented, and production-ready.
