package com.spotifyyoutube.migrator.common.exception;

public class ExternalProviderException extends RuntimeException {
    
    private final Integer providerStatusCode;
    private final String retryAfter;
    private final String providerReason;

    public ExternalProviderException(String message) {
        this(message, null, null, null);
    }

    public ExternalProviderException(String message, Integer providerStatusCode) {
        this(message, providerStatusCode, null, null);
    }

    public ExternalProviderException(String message, Integer providerStatusCode, String retryAfter) {
        this(message, providerStatusCode, retryAfter, null);
    }

    public ExternalProviderException(String message, Integer providerStatusCode, String retryAfter, String providerReason) {
        super(message);
        this.providerStatusCode = providerStatusCode;
        this.retryAfter = retryAfter;
        this.providerReason = providerReason;
    }

    public ExternalProviderException(String message, Throwable cause) {
        super(message, cause);
        this.providerStatusCode = null;
        this.retryAfter = null;
        this.providerReason = null;
    }

    public Integer getProviderStatusCode() {
        return providerStatusCode;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    public String getProviderReason() {
        return providerReason;
    }
}
