package com.spotifyyoutube.migrator.common.exception;

public class ExternalProviderException extends RuntimeException {
    
    private final Integer providerStatusCode;
    private final String retryAfter;

    public ExternalProviderException(String message) {
        super(message);
        this.providerStatusCode = null;
        this.retryAfter = null;
    }

    public ExternalProviderException(String message, Integer providerStatusCode, String retryAfter) {
        super(message);
        this.providerStatusCode = providerStatusCode;
        this.retryAfter = retryAfter;
    }

    public ExternalProviderException(String message, Throwable cause) {
        super(message, cause);
        this.providerStatusCode = null;
        this.retryAfter = null;
    }

    public Integer getProviderStatusCode() {
        return providerStatusCode;
    }

    public String getRetryAfter() {
        return retryAfter;
    }
}
