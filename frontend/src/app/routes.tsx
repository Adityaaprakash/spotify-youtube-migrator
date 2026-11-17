import { Routes, Route, Navigate } from 'react-router-dom';
import { AppLayout } from '@/components/layout/AppLayout';
import { LoginPage } from '@/features/auth/LoginPage';
import { RegisterPage } from '@/features/auth/RegisterPage';
import { ProtectedRoute, PublicOnlyRoute } from '@/features/auth/ProtectedRoute';
import { DashboardPage } from '@/features/dashboard/DashboardPage';
import { PlaylistsPage } from '@/features/playlists/PlaylistsPage';
import { PlaylistDetailPage } from '@/features/playlists/PlaylistDetailPage';
import { MigrationsPage } from '@/features/migrations/MigrationsPage';
import { MigrationDetailPage } from '@/features/migrations/MigrationDetailPage';
import { MigrationReviewPage } from '@/features/migrations/MigrationReviewPage';
import { MigrationProgressPage } from '@/features/migrations/MigrationProgressPage';
import { SettingsPage } from '@/features/settings/SettingsPage';
import { NotFoundPage } from '@/features/notFound/NotFoundPage';

export function AppRoutes() {
  return (
    <Routes>
      {/* Anonymous-only Authentication Routes */}
      <Route
        path="/login"
        element={
          <PublicOnlyRoute>
            <LoginPage />
          </PublicOnlyRoute>
        }
      />
      <Route
        path="/register"
        element={
          <PublicOnlyRoute>
            <RegisterPage />
          </PublicOnlyRoute>
        }
      />

      {/* Protected Application Routes requiring authenticated session */}
      <Route
        element={
          <ProtectedRoute>
            <AppLayout />
          </ProtectedRoute>
        }
      >
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/playlists" element={<PlaylistsPage />} />
        <Route path="/playlists/:id" element={<PlaylistDetailPage />} />
        <Route path="/migrations" element={<MigrationsPage />} />
        <Route path="/migrations/:id" element={<MigrationDetailPage />} />
        <Route path="/migrations/:id/review" element={<MigrationReviewPage />} />
        <Route path="/migrations/:id/progress" element={<MigrationProgressPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
