import { ApiError, ApiException } from './types';

const API_BASE_URL = (typeof import.meta !== 'undefined' && import.meta.env?.VITE_API_URL) || '/api';

/**
 * Extracts the XSRF-TOKEN cookie value from document.cookie for CSRF protection.
 */
function getCsrfToken(): string | null {
  if (typeof document === 'undefined') {
    return null;
  }
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

export interface RequestOptions extends RequestInit {
  params?: Record<string, string | number | boolean | undefined | null>;
  idempotencyKey?: string;
}

/**
 * Robust HTTP client configured for backend integration:
 * - Credentials included for session cookie transmission (JSESSIONID).
 * - Automatic X-XSRF-TOKEN header injection on mutating requests.
 * - Normalized ApiError parsing throwing strongly typed ApiException.
 */
export async function fetchClient<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
  const { params, headers, idempotencyKey, ...fetchOptions } = options;

  let url = endpoint.startsWith('http') ? endpoint : `${API_BASE_URL}${endpoint}`;

  if (params) {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) {
        query.append(key, String(value));
      }
    }
    const queryString = query.toString();
    if (queryString) {
      url += (url.includes('?') ? '&' : '?') + queryString;
    }
  }

  const requestHeaders = new Headers(headers);

  if (!requestHeaders.has('Content-Type') && !(fetchOptions.body instanceof FormData)) {
    requestHeaders.set('Content-Type', 'application/json');
  }

  // Inject CSRF token on mutating HTTP methods
  const method = (fetchOptions.method || 'GET').toUpperCase();
  if (['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)) {
    const csrfToken = getCsrfToken();
    if (csrfToken && !requestHeaders.has('X-XSRF-TOKEN')) {
      requestHeaders.set('X-XSRF-TOKEN', csrfToken);
    }
  }

  // Inject idempotency key if provided
  if (idempotencyKey && !requestHeaders.has('Idempotency-Key')) {
    requestHeaders.set('Idempotency-Key', idempotencyKey);
  }

  const response = await fetch(url, {
    ...fetchOptions,
    headers: requestHeaders,
    credentials: 'include',
  });

  if (!response.ok) {
    let errorData: ApiError | null = null;
    try {
      errorData = await response.json();
    } catch {
      // response was not JSON
    }

    const fallbackError: ApiError = {
      code: `HTTP_${response.status}`,
      message: response.statusText || 'An unexpected server error occurred',
      timestamp: new Date().toISOString(),
      path: endpoint,
    };

    throw new ApiException(response.status, errorData || fallbackError);
  }

  if (response.status === 204) {
    return undefined as unknown as T;
  }

  return response.json();
}

export const apiClient = {
  get: <T>(endpoint: string, options?: RequestOptions) =>
    fetchClient<T>(endpoint, { ...options, method: 'GET' }),

  post: <T>(endpoint: string, body?: unknown, options?: RequestOptions) =>
    fetchClient<T>(endpoint, {
      ...options,
      method: 'POST',
      body: body ? JSON.stringify(body) : undefined,
    }),

  put: <T>(endpoint: string, body?: unknown, options?: RequestOptions) =>
    fetchClient<T>(endpoint, {
      ...options,
      method: 'PUT',
      body: body ? JSON.stringify(body) : undefined,
    }),

  patch: <T>(endpoint: string, body?: unknown, options?: RequestOptions) =>
    fetchClient<T>(endpoint, {
      ...options,
      method: 'PATCH',
      body: body ? JSON.stringify(body) : undefined,
    }),

  delete: <T>(endpoint: string, options?: RequestOptions) =>
    fetchClient<T>(endpoint, { ...options, method: 'DELETE' }),
};
