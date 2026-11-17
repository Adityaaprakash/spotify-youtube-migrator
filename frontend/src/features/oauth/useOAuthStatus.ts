import { useState, useCallback } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query/queryKeys';
import { useAuth } from '@/features/auth/useAuth';
import { oauthApi, OAuthProvider } from './api';
import { OAuthConnectionStatus } from '@/types';

export function useOAuthStatus(provider: OAuthProvider) {
  const queryClient = useQueryClient();
  const { isAuthenticated } = useAuth();
  const [isConnecting, setIsConnecting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const queryKey = queryKeys.oauth.provider(provider.toUpperCase());

  const {
    data: status,
    isPending: isLoading,
    error: queryError,
    refetch,
  } = useQuery<OAuthConnectionStatus, Error>({
    queryKey,
    queryFn: () => oauthApi.getStatus(provider),
    enabled: isAuthenticated,
    staleTime: 60 * 1000, // 1 minute
    refetchOnWindowFocus: true, // Auto-sync when returning from OAuth redirect
  });

  const disconnectMutation = useMutation({
    mutationFn: () => oauthApi.disconnect(provider),
    onSuccess: () => {
      setActionError(null);
      void queryClient.invalidateQueries({ queryKey });
    },
    onError: (err: Error) => {
      setActionError(err.message || `Failed to disconnect ${provider}.`);
    },
  });

  const connect = useCallback(async () => {
    setIsConnecting(true);
    setActionError(null);
    try {
      const response = await oauthApi.getAuthorizeUrl(provider);
      if (response && response.url) {
        // Safe top-level browser redirect to provider authorization page
        window.location.assign(response.url);
      } else {
        throw new Error('No authorization redirect URL returned by server.');
      }
    } catch (err: unknown) {
      setIsConnecting(false);
      const message = err instanceof Error ? err.message : 'Failed to initiate OAuth authorization.';
      setActionError(message);
    }
  }, [provider]);

  const disconnect = useCallback(async () => {
    setActionError(null);
    await disconnectMutation.mutateAsync();
  }, [disconnectMutation]);

  const clearError = useCallback(() => {
    setActionError(null);
  }, []);

  return {
    status: status ?? null,
    isConnected: Boolean(status?.connected),
    isLoading,
    isConnecting,
    isDisconnecting: disconnectMutation.isPending,
    error: actionError || (queryError ? queryError.message : null),
    connect,
    disconnect,
    refetch,
    clearError,
  };
}
