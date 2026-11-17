import { apiClient } from '@/lib/api/client';
import { UserResponse, LoginRequest, RegisterRequest } from './types';

export const authApi = {
  /**
   * Fetches the current authenticated user session.
   * Returns UserResponse if session is valid, or throws ApiException with 401/403 if anonymous.
   */
  getCurrentUser: async (): Promise<UserResponse> => {
    return apiClient.get<UserResponse>('/auth/me');
  },

  /**
   * Establishes a session for the user with email and password.
   */
  login: async (credentials: LoginRequest): Promise<UserResponse> => {
    return apiClient.post<UserResponse>('/auth/login', credentials);
  },

  /**
   * Registers a new account and automatically establishes a session.
   */
  register: async (data: RegisterRequest): Promise<UserResponse> => {
    return apiClient.post<UserResponse>('/auth/register', data);
  },

  /**
   * Terminates the current server session and clears security context.
   */
  logout: async (): Promise<void> => {
    return apiClient.post<void>('/auth/logout');
  },
};
