package com.spotifyyoutube.migrator.common.exception;

public class ExternalProviderException extends RuntimeException {
    public ExternalProviderException(String message) {
        super(message);
    }

    public ExternalProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
