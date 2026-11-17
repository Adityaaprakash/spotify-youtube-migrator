import { useState } from 'react';
import { PageHeader } from '@/components/shared/PageHeader';
import { EmptyState } from '@/components/shared/EmptyState';
import { ErrorState } from '@/components/shared/ErrorState';
import { CardSkeleton } from '@/components/shared/LoadingSkeleton';
import { Button } from '@/components/ui/button';
import { ListMusic, ChevronLeft, ChevronRight, Loader2, Music } from 'lucide-react';
import { usePlaylists } from './hooks/usePlaylists';
import { PlaylistCard } from './components/PlaylistCard';

const PAGE_SIZE = 20;

export function PlaylistsPage() {
  const [offset, setOffset] = useState(0);

  const {
    playlists,
    total,
    hasNext,
    isLoading,
    isError,
    error,
    refetch,
    isSpotifyConnected,
    isConnectingSpotify,
    connectSpotify,
  } = usePlaylists({ limit: PAGE_SIZE, offset });

  const currentPage = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.ceil(total / PAGE_SIZE) || 1;

  const handlePreviousPage = () => {
    setOffset((prev) => Math.max(0, prev - PAGE_SIZE));
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const handleNextPage = () => {
    if (hasNext || offset + PAGE_SIZE < total) {
      setOffset((prev) => prev + PAGE_SIZE);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
  };

  return (
    <div className="space-y-6">
      <PageHeader
        title="Spotify Playlists"
        description="Select a Spotify playlist to inspect tracks and prepare your migration to YouTube Music."
      />

      {/* Disconnected State */}
      {!isSpotifyConnected ? (
        <EmptyState
          icon={ListMusic}
          title="Connect Spotify to Access Playlists"
          description="Your Spotify account is not connected. Connect your Spotify account to discover your personal and followed playlists."
          action={
            <Button
              onClick={connectSpotify}
              disabled={isConnectingSpotify}
              className="gap-2 bg-spotify hover:bg-spotify/90 text-white"
            >
              {isConnectingSpotify ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" /> Connecting to Spotify...
                </>
              ) : (
                <>
                  <Music className="h-4 w-4" /> Connect Spotify
                </>
              )}
            </Button>
          }
        />
      ) : isLoading ? (
        /* Loading Skeleton Grid */
        <div className="space-y-4">
          <div className="h-4 w-48 bg-muted animate-pulse rounded" />
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
            {Array.from({ length: 6 }).map((_, i) => (
              <CardSkeleton key={i} className="min-h-[220px]" />
            ))}
          </div>
        </div>
      ) : isError ? (
        /* Error State */
        <ErrorState
          error={error}
          title="Failed to Load Playlists"
          onRetry={() => void refetch()}
        />
      ) : playlists.length === 0 ? (
        /* Empty Playlists State */
        <EmptyState
          icon={ListMusic}
          title="No Playlists Found"
          description="No playlists were found on your Spotify account. Create or follow playlists on Spotify, then refresh."
          action={
            <Button variant="outline" size="sm" onClick={() => void refetch()}>
              Refresh Playlists
            </Button>
          }
        />
      ) : (
        /* Playlists Grid & Pagination */
        <div className="space-y-6">
          <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2 pb-2 border-b border-border/50 text-xs text-muted-foreground">
            <span>
              Showing{' '}
              <strong className="text-foreground">
                {offset + 1}–{Math.min(offset + PAGE_SIZE, total)}
              </strong>{' '}
              of <strong className="text-foreground">{total}</strong> playlists
            </span>
            <span>
              Page <strong className="text-foreground">{currentPage}</strong> of{' '}
              <strong className="text-foreground">{totalPages}</strong>
            </span>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
            {playlists.map((playlist) => (
              <PlaylistCard key={playlist.id} playlist={playlist} />
            ))}
          </div>

          {/* Pagination Controls */}
          {totalPages > 1 && (
            <div className="flex items-center justify-between pt-4 border-t border-border/50">
              <Button
                variant="outline"
                size="sm"
                onClick={handlePreviousPage}
                disabled={offset === 0}
                className="gap-1.5 text-xs"
              >
                <ChevronLeft className="h-4 w-4" /> Previous
              </Button>

              <span className="text-xs text-muted-foreground">
                Page {currentPage} of {totalPages}
              </span>

              <Button
                variant="outline"
                size="sm"
                onClick={handleNextPage}
                disabled={!hasNext && offset + PAGE_SIZE >= total}
                className="gap-1.5 text-xs"
              >
                Next <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
