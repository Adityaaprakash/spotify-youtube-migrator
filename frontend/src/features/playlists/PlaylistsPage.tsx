import { PageHeader } from '@/components/shared/PageHeader';
import { EmptyState } from '@/components/shared/EmptyState';
import { ListMusic } from 'lucide-react';

export function PlaylistsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Playlists"
        description="Select a Spotify playlist to preview matches and configure your migration."
      />

      <EmptyState
        icon={ListMusic}
        title="Playlist Discovery"
        description="Playlist listing and selection will be connected in Phase 15D."
      />
    </div>
  );
}
