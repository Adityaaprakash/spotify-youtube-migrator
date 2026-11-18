package com.spotifyyoutube.migrator.common.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties governing API rate limiting across endpoints (Phase 16D).
 */
@Component
@ConfigurationProperties(prefix = "security.rate-limiting")
public class RateLimitProperties {

    private boolean enabled = true;
    private boolean trustForwardedHeaders = false;
    private int maxTrackedKeys = 10000;

    private PolicyProperties login = new PolicyProperties(10, 60, 60);
    private PolicyProperties register = new PolicyProperties(5, 60, 60);
    private PolicyProperties oauth = new PolicyProperties(15, 60, 30);
    private PolicyProperties search = new PolicyProperties(30, 60, 15);
    private PolicyProperties migration = new PolicyProperties(10, 60, 30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTrustForwardedHeaders() {
        return trustForwardedHeaders;
    }

    public void setTrustForwardedHeaders(boolean trustForwardedHeaders) {
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    public int getMaxTrackedKeys() {
        return maxTrackedKeys;
    }

    public void setMaxTrackedKeys(int maxTrackedKeys) {
        this.maxTrackedKeys = maxTrackedKeys;
    }

    public PolicyProperties getLogin() {
        return login;
    }

    public void setLogin(PolicyProperties login) {
        this.login = login;
    }

    public PolicyProperties getRegister() {
        return register;
    }

    public void setRegister(PolicyProperties register) {
        this.register = register;
    }

    public PolicyProperties getOauth() {
        return oauth;
    }

    public void setOauth(PolicyProperties oauth) {
        this.oauth = oauth;
    }

    public PolicyProperties getSearch() {
        return search;
    }

    public void setSearch(PolicyProperties search) {
        this.search = search;
    }

    public PolicyProperties getMigration() {
        return migration;
    }

    public void setMigration(PolicyProperties migration) {
        this.migration = migration;
    }

    public static class PolicyProperties {
        private int maxRequests;
        private long windowSeconds;
        private long retryAfterSeconds;

        public PolicyProperties() {
        }

        public PolicyProperties(int maxRequests, long windowSeconds, long retryAfterSeconds) {
            this.maxRequests = maxRequests;
            this.windowSeconds = windowSeconds;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(int maxRequests) {
            this.maxRequests = maxRequests;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }

        public void setRetryAfterSeconds(long retryAfterSeconds) {
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}
