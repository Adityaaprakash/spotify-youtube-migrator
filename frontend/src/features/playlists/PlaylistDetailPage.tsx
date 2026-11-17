import { useParams, Link } from 'react-router-dom';
import { PageHeader } from '@/components/shared/PageHeader';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { ArrowLeft } from 'lucide-react';

export function PlaylistDetailPage() {
  const { id } = useParams<{ id: string }>();

  return (
    <div className="space-y-6">
      <div className="mb-2">
        <Link to="/playlists">
          <Button variant="ghost" size="sm" className="gap-1.5 text-xs text-muted-foreground">
            <ArrowLeft className="h-3.5 w-3.5" /> Back to playlists
          </Button>
        </Link>
      </div>

      <PageHeader
        title="Playlist Details"
        description={`Inspecting Spotify playlist ID: ${id || 'unknown'}`}
      />

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Track Inspection & Migration Configuration</CardTitle>
          <CardDescription>Track listing and preview generation will be connected in Phase 15D.</CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Playlist ID: <code className="bg-secondary px-2 py-0.5 rounded text-xs text-foreground">{id}</code>
        </CardContent>
      </Card>
    </div>
  );
}
