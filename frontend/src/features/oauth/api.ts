import { apiClient } from '@/lib/api/client';
import { OAuthConnectionStatus } from '@/types';

export type OAuthProvider = 'spotify' | 'google';

export interface OAuthAuthorizationResponse {
  url: string;
}

export const oauthApi = {
  /**
   * Checks the connection and token lifecycle status for a provider.
   */
  getStatus: async (provider: OAuthProvider): Promise<OAuthConnectionStatus> => {
    return apiClient.get<OAuthConnectionStatus>(`/oauth/${provider.toLowerCase()}`);
  },

  /**
   * Generates the secure OAuth authorization URL for top-level browser redirect.
   */
  getAuthorizeUrl: async (provider: OAuthProvider): Promise<OAuthAuthorizationResponse> => {
    return apiClient.get<OAuthAuthorizationResponse>(`/oauth/${provider.toLowerCase()}/authorize`);
  },

  /**
   * Disconnects the provider account and deletes local tokens.
   */
  disconnect: async (provider: OAuthProvider): Promise<void> => {
    return apiClient.delete<void>(`/oauth/${provider.toLowerCase()}`);
  },
};
