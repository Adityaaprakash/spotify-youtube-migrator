/**
 * Standard API error payload returned by backend GlobalExceptionHandler.
 */
export interface ApiError {
  code: string;
  message: string;
  timestamp: string;
  path: string;
}

/**
 * Standard backend error codes mapped from ErrorCode enum.
 */
export const ErrorCode = {
  INVALID_REQUEST: 'INVALID_REQUEST',
  UNAUTHORIZED: 'UNAUTHORIZED',
  FORBIDDEN: 'FORBIDDEN',
  NOT_FOUND: 'NOT_FOUND',
  CONFLICT: 'CONFLICT',
  INVALID_STATE: 'INVALID_STATE',
  EXTERNAL_PROVIDER_ERROR: 'EXTERNAL_PROVIDER_ERROR',
  TOO_MANY_REQUESTS: 'TOO_MANY_REQUESTS',
  INTERNAL_SERVER_ERROR: 'INTERNAL_SERVER_ERROR',
  IDEMPOTENCY_FINGERPRINT_MISMATCH: 'IDEMPOTENCY_FINGERPRINT_MISMATCH',
} as const;

export type ErrorCodeType = (typeof ErrorCode)[keyof typeof ErrorCode];

/**
 * Normalized exception representing a failed HTTP or API call.
 */
export class ApiException extends Error {
  public readonly status: number;
  public readonly details: ApiError;

  constructor(status: number, details: ApiError) {
    super(details.message || `API request failed with status ${status}`);
    this.name = 'ApiException';
    this.status = status;
    this.details = details;
  }

  get code(): string {
    return this.details.code;
  }

  get path(): string {
    return this.details.path;
  }

  get timestamp(): string {
    return this.details.timestamp;
  }
}
