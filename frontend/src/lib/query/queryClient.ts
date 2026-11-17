import { QueryClient } from '@tanstack/react-query';
import { ApiException } from '../api/types';

/**
 * Shared QueryClient configured with reliable caching, retry, and error-handling defaults.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 60 * 1000, // 1 minute
      gcTime: 5 * 60 * 1000, // 5 minutes
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        // Do not retry client errors or non-recoverable states
        if (error instanceof ApiException) {
          if ([400, 401, 403, 404, 409].includes(error.status)) {
            return false;
          }
        }
        // Retry up to 2 times for transient/network errors
        return failureCount < 2;
      },
    },
    mutations: {
      retry: false, // Never auto-retry mutating operations
    },
  },
});
