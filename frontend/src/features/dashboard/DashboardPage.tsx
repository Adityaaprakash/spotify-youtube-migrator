import { PageHeader } from '@/components/shared/PageHeader';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { ListMusic, History, ArrowRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useAuth } from '@/features/auth/useAuth';
import { OAuthConnectionCard } from '@/features/oauth/OAuthConnectionCard';

export function DashboardPage() {
  const { user } = useAuth();

  return (
    <div className="space-y-6">
      <PageHeader
        title={user?.displayName ? `Welcome back, ${user.displayName}` : 'Dashboard'}
        description="Overview of your connected music services and playlist migration activity."
      />

      <div className="space-y-4">
        <div>
          <h2 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider mb-3">
            Connected Services
          </h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <OAuthConnectionCard
              provider="spotify"
              title="Spotify Source"
              description="Connect to read playlists and source tracks for migration."
            />
            <OAuthConnectionCard
              provider="google"
              title="YouTube Music Destination"
              description="Connect to create and populate destination playlists."
            />
          </div>
        </div>

        <div>
          <h2 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider mb-3">
            Migration Tools
          </h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <Card className="border-border/80 bg-card/60">
              <CardHeader>
                <div className="flex items-center justify-between">
                  <CardTitle className="text-base flex items-center gap-2">
                    <ListMusic className="h-4 w-4 text-emerald-400" />
                    Spotify Playlists
                  </CardTitle>
                  <Badge variant="spotify" size="sm">
                    Phase 15D
                  </Badge>
                </div>
                <CardDescription>
                  Discover, inspect, and select Spotify playlists to migrate
                </CardDescription>
              </CardHeader>
              <CardContent className="pt-2">
                <Link
                  to="/playlists"
                  className="inline-flex items-center gap-2 text-sm text-primary hover:underline font-medium"
                >
                  Browse playlists <ArrowRight className="h-3.5 w-3.5" />
                </Link>
              </CardContent>
            </Card>

            <Card className="border-border/80 bg-card/60">
              <CardHeader>
                <div className="flex items-center justify-between">
                  <CardTitle className="text-base flex items-center gap-2">
                    <History className="h-4 w-4 text-rose-400" />
                    Migration History
                  </CardTitle>
                  <Badge variant="youtube" size="sm">
                    Phase 15G
                  </Badge>
                </div>
                <CardDescription>
                  Track progress, review audit timelines, and resume runs
                </CardDescription>
              </CardHeader>
              <CardContent className="pt-2">
                <Link
                  to="/migrations"
                  className="inline-flex items-center gap-2 text-sm text-primary hover:underline font-medium"
                >
                  View migrations <ArrowRight className="h-3.5 w-3.5" />
                </Link>
              </CardContent>
            </Card>
          </div>
        </div>
      </div>
    </div>
  );
}
