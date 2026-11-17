/**
 * Hierarchical query key factory for predictable caching and invalidation across features.
 */
export const queryKeys = {
  auth: {
    all: ['auth'] as const,
    me: () => [...queryKeys.auth.all, 'me'] as const,
  },
  oauth: {
    all: ['oauth'] as const,
    provider: (provider: string) => [...queryKeys.oauth.all, provider] as const,
  },
  playlists: {
    all: ['playlists'] as const,
    lists: () => [...queryKeys.playlists.all, 'list'] as const,
    list: (params?: { limit?: number; offset?: number }) =>
      [...queryKeys.playlists.lists(), params] as const,
    details: () => [...queryKeys.playlists.all, 'detail'] as const,
    detail: (id: string) => [...queryKeys.playlists.details(), id] as const,
    tracks: (id: string) => [...queryKeys.playlists.detail(id), 'tracks'] as const,
  },
  reviewSessions: {
    all: ['review-sessions'] as const,
    lists: () => [...queryKeys.reviewSessions.all, 'list'] as const,
    detail: (id: string) => [...queryKeys.reviewSessions.all, 'detail', id] as const,
    progress: (id: string) => [...queryKeys.reviewSessions.detail(id), 'progress'] as const,
    tracks: (id: string) => [...queryKeys.reviewSessions.detail(id), 'tracks'] as const,
  },
  migrations: {
    all: ['migrations'] as const,
    history: (params?: { page?: number; size?: number; status?: string }) =>
      [...queryKeys.migrations.all, 'history', params] as const,
    detail: (id: string) => [...queryKeys.migrations.all, 'detail', id] as const,
    tracks: (id: string) => [...queryKeys.migrations.detail(id), 'tracks'] as const,
    progress: (id: string) => [...queryKeys.migrations.detail(id), 'progress'] as const,
    timeline: (id: string, params?: { page?: number; size?: number }) =>
      [...queryKeys.migrations.detail(id), 'timeline', params] as const,
  },
};
