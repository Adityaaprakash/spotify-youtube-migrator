package com.spotifyyoutube.migrator.common.exception;

public record ApiError(
        ErrorCode code,
        String message,
        String timestamp,
        String path
) {}
