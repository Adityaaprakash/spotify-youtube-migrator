import { PageHeader } from '@/components/shared/PageHeader';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { ListMusic, History, ArrowRight } from 'lucide-react';
import { Link } from 'react-router-dom';

export function DashboardPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Dashboard"
        description="Overview of your connected music services and playlist migration activity."
      />

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle className="text-base flex items-center gap-2">
                <ListMusic className="h-4 w-4 text-emerald-400" />
                Spotify Playlists
              </CardTitle>
              <Badge variant="spotify" size="sm">Spotify</Badge>
            </div>
            <CardDescription>Discover and select playlists for migration</CardDescription>
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

        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle className="text-base flex items-center gap-2">
                <History className="h-4 w-4 text-rose-400" />
                Migration History
              </CardTitle>
              <Badge variant="youtube" size="sm">YouTube Music</Badge>
            </div>
            <CardDescription>Track active and past migration runs</CardDescription>
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
  );
}
