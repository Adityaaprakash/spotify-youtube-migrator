import React, { useState, useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { ApiException } from '@/lib/api/types';
import { authApi } from './api';
import { AuthContext } from './context';
import { AuthContextValue, UserResponse, LoginRequest, RegisterRequest } from './types';

export interface AuthProviderProps {
  children: React.ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
  const queryClient = useQueryClient();
  const [isSessionExpired, setIsSessionExpired] = useState(false);

  // TanStack Query managing server-backed user session state
  const {
    data: user,
    isPending: isLoading,
    error: queryError,
    refetch,
  } = useQuery<UserResponse | null, Error>({
    queryKey: queryKeys.auth.me(),
    queryFn: async () => {
      try {
        const response = await authApi.getCurrentUser();
        return response;
      } catch (err) {
        if (err instanceof ApiException && (err.status === 401 || err.status === 403)) {
          // Normal anonymous / unauthenticated state: return null instead of throwing
          return null;
        }
        // Unexpected network or server error: propagate to error state
        throw err;
      }
    },
    staleTime: 5 * 60 * 1000, // 5 minutes fresh
    gcTime: 10 * 60 * 1000,
    retry: (failureCount, error) => {
      if (error instanceof ApiException && (error.status === 401 || error.status === 403)) {
        return false;
      }
      return failureCount < 1;
    },
    refetchOnWindowFocus: false,
  });

  const isAuthenticated = Boolean(user);

  const login = useCallback(
    async (credentials: LoginRequest): Promise<UserResponse> => {
      const loggedInUser = await authApi.login(credentials);
      queryClient.setQueryData(queryKeys.auth.me(), loggedInUser);
      setIsSessionExpired(false);
      // Invalidate OAuth provider statuses so fresh connection data is queried
      void queryClient.invalidateQueries({ queryKey: queryKeys.oauth.all });
      return loggedInUser;
    },
    [queryClient]
  );

  const register = useCallback(
    async (data: RegisterRequest): Promise<UserResponse> => {
      const registeredUser = await authApi.register(data);
      // Backend automatically establishes session on register
      queryClient.setQueryData(queryKeys.auth.me(), registeredUser);
      setIsSessionExpired(false);
      void queryClient.invalidateQueries({ queryKey: queryKeys.oauth.all });
      return registeredUser;
    },
    [queryClient]
  );

  const logout = useCallback(async (): Promise<void> => {
    try {
      await authApi.logout();
    } finally {
      queryClient.setQueryData(queryKeys.auth.me(), null);
      void queryClient.invalidateQueries({ queryKey: queryKeys.oauth.all });
      setIsSessionExpired(false);
    }
  }, [queryClient]);

  const refreshSession = useCallback(async (): Promise<UserResponse | null> => {
    const result = await refetch();
    return result.data ?? null;
  }, [refetch]);

  const dismissSessionExpired = useCallback(() => {
    setIsSessionExpired(false);
  }, []);

  const value: AuthContextValue = {
    user: user ?? null,
    isLoading,
    isAuthenticated,
    isSessionExpired,
    error: queryError ?? null,
    login,
    register,
    logout,
    refreshSession,
    dismissSessionExpired,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
