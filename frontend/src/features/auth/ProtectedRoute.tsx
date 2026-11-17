import React from 'react';
import { Navigate, useLocation, Outlet } from 'react-router-dom';
import { useAuth } from './useAuth';
import { PageLoadingSkeleton } from '@/components/shared/LoadingSkeleton';

export interface ProtectedRouteProps {
  children?: React.ReactNode;
}

/**
 * Route guard that ensures only authenticated users can access child routes.
 * While session is initializing, renders a full-page loading skeleton.
 * If unauthenticated, redirects to /login preserving the intended destination.
 */
export function ProtectedRoute({ children }: ProtectedRouteProps) {
  const { isAuthenticated, isLoading } = useAuth();
  const location = useLocation();

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center p-6 bg-background">
        <div className="w-full max-w-md space-y-4">
          <PageLoadingSkeleton />
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  return children ? <>{children}</> : <Outlet />;
}

export interface PublicOnlyRouteProps {
  children?: React.ReactNode;
}

/**
 * Route guard for anonymous-only pages (such as /login and /register).
 * If the user is already authenticated, redirects directly to /dashboard.
 */
export function PublicOnlyRoute({ children }: PublicOnlyRouteProps) {
  const { isAuthenticated, isLoading } = useAuth();
  const location = useLocation();

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center p-6 bg-background">
        <div className="w-full max-w-md space-y-4">
          <PageLoadingSkeleton />
        </div>
      </div>
    );
  }

  if (isAuthenticated) {
    const from = (location.state as { from?: Location })?.from?.pathname || '/dashboard';
    return <Navigate to={from} replace />;
  }

  return children ? <>{children}</> : <Outlet />;
}
