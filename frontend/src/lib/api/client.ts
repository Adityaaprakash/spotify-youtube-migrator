export interface ApiError {
  code: string;
  message: string;
  timestamp: string;
  path: string;
}

export class ApiException extends Error {
  constructor(public readonly details: ApiError) {
    super(details.message);
    this.name = 'ApiException';
  }
}

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api';

export async function fetchClient<T>(endpoint: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${endpoint}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => null);
    if (errorData?.code) {
      throw new ApiException(errorData);
    }
    throw new Error(response.statusText);
  }

  return response.json();
}
