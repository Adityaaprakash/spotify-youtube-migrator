import { UserProfile } from '@/types';

export type UserResponse = UserProfile;

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

export interface AuthContextValue {
  user: UserResponse | null;
  isLoading: boolean;
  isAuthenticated: boolean;
  isSessionExpired: boolean;
  error: Error | null;
  login: (credentials: LoginRequest) => Promise<UserResponse>;
  register: (data: RegisterRequest) => Promise<UserResponse>;
  logout: () => Promise<void>;
  refreshSession: () => Promise<UserResponse | null>;
  dismissSessionExpired: () => void;
}
