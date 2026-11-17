/**
 * Application navigation item definition.
 */
export interface NavItem {
  title: string;
  href: string;
  disabled?: boolean;
  external?: boolean;
  icon?: React.ComponentType<{ className?: string }>;
}

export type UserStatus = 'ACTIVE' | 'DISABLED';

/**
 * User profile contract matching backend UserResponse.
 */
export interface UserProfile {
  id: string;
  email: string;
  displayName: string;
  status: UserStatus;
}

/**
 * OAuth connection status contract matching backend OAuthConnectionStatusDto.
 */
export interface OAuthConnectionStatus {
  provider: 'SPOTIFY' | 'GOOGLE' | string;
  connected: boolean;
  providerUserId?: string | null;
  expiresAt?: string | null;
  status: 'CONNECTED' | 'DISCONNECTED' | 'EXPIRED' | 'REVOKED' | string;
}
