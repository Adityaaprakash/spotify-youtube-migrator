import { useParams, Link } from 'react-router-dom';
import { PageHeader } from '@/components/shared/PageHeader';
import { EmptyState } from '@/components/shared/EmptyState';
import { ErrorState } from '@/components/shared/ErrorState';
import { TableRowSkeleton } from '@/components/shared/LoadingSkeleton';
import { Card, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { ArrowLeft, ExternalLink, Sparkles, Music, Loader2, ListMusic } from 'lucide-react';
import { usePlaylistDetail } from './hooks/usePlaylistDetail';
import { usePlaylistTracks } from './hooks/usePlaylistTracks';
import { TrackTable } from './components/TrackTable';
import { sanitizeDescription } from './utils';

export function PlaylistDetailPage() {
  const { id } = useParams<{ id: string }>();

  const {
    playlist,
    isLoading: isLoadingDetail,
    isError: isErrorDetail,
    error: errorDetail,
    refetch: refetchDetail,
    isSpotifyConnected,
    isConnectingSpotify,
    connectSpotify,
  } = usePlaylistDetail(id);

  const {
    tracks,
    isLoading: isLoadingTracks,
    isError: isErrorTracks,
    error: errorTracks,
    refetch: refetchTracks,
  } = usePlaylistTracks(id);

  const isLoading = isLoadingDetail || isLoadingTracks;
  const error = errorDetail || errorTracks;

  const handleRetryAll = () => {
    void refetchDetail();
    void refetchTracks();
  };

  return (
    <div className="space-y-6">
      {/* Back Navigation Link */}
      <div>
        <Link to="/playlists">
          <Button variant="ghost" size="sm" className="gap-1.5 text-xs text-muted-foreground hover:text-foreground">
            <ArrowLeft className="h-3.5 w-3.5" /> Back to playlists
          </Button>
        </Link>
      </div>

      {/* Disconnected Spotify State */}
      {!isSpotifyConnected ? (
        <EmptyState
          icon={ListMusic}
          title="Connect Spotify to View Playlist"
          description="Your Spotify account is not connected. Connect your Spotify account to inspect playlist metadata and tracks."
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
      ) : isErrorDetail || isErrorTracks ? (
        /* Error State */
        <ErrorState
          error={error}
          title="Failed to Load Playlist"
          onRetry={handleRetryAll}
        />
      ) : isLoading ? (
        /* Loading Skeleton */
        <div className="space-y-6">
          <Card className="border-border/60">
            <CardHeader className="p-6">
              <div className="flex flex-col md:flex-row items-start gap-6">
                <Skeleton className="h-36 w-36 rounded-md shrink-0" />
                <div className="space-y-3 flex-1">
                  <div className="flex gap-2">
                    <Skeleton className="h-5 w-20" />
                    <Skeleton className="h-5 w-24" />
                  </div>
                  <Skeleton className="h-8 w-2/3" />
                  <Skeleton className="h-4 w-full max-w-lg" />
                  <Skeleton className="h-9 w-40 mt-4" />
                </div>
              </div>
            </CardHeader>
          </Card>

          <div className="space-y-2">
            <Skeleton className="h-9 w-64" />
            <div className="rounded-lg border border-border/60 overflow-hidden bg-card">
              {Array.from({ length: 6 }).map((_, i) => (
                <TableRowSkeleton key={i} columns={4} />
              ))}
            </div>
          </div>
        </div>
      ) : playlist ? (
        /* Playlist Content */
        <div className="space-y-8">
          {/* Playlist Metadata Hero Card */}
          <Card className="border-border/80 overflow-hidden bg-card/60 backdrop-blur-sm shadow-sm">
            <CardHeader className="p-6">
              <div className="flex flex-col md:flex-row items-start gap-6">
                {/* Artwork Placeholder / Thumbnail */}
                <div className="h-32 w-32 md:h-36 md:w-36 rounded-lg bg-secondary/80 border border-border/60 flex flex-col items-center justify-center shrink-0 shadow-inner">
                  <Music className="h-12 w-12 text-spotify opacity-80" />
                  <span className="text-[10px] uppercase font-semibold text-muted-foreground/70 tracking-widest mt-1">
                    Spotify
                  </span>
                </div>

                {/* Metadata & Actions */}
                <div className="flex-1 space-y-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="spotify" size="sm">
                      Spotify
                    </Badge>
                    <Badge variant="secondary" size="sm">
                      {tracks.length} {tracks.length === 1 ? 'track' : 'tracks'}
                    </Badge>
                    {playlist.url && (
                      <a
                        href={playlist.url}
                        target="_blank"
                        rel="noreferrer noopener"
                        className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors ml-1"
                      >
                        Open on Spotify <ExternalLink className="h-3 w-3" />
                      </a>
                    )}
                  </div>

                  <CardTitle className="text-2xl font-bold tracking-tight text-foreground">
                    {playlist.name}
                  </CardTitle>

                  {playlist.description && (
                    <CardDescription className="text-xs text-muted-foreground max-w-2xl leading-relaxed">
                      {sanitizeDescription(playlist.description)}
                    </CardDescription>
                  )}

                  {/* Migration Preview Entry Point — Boundary to Phase 15E */}
                  <div className="pt-2 flex items-center gap-3">
                    <Button
                      variant="default"
                      size="sm"
                      disabled
                      title="Migration planning and review will be enabled in Phase 15E"
                      className="gap-2 bg-primary/80 opacity-70 cursor-not-allowed text-primary-foreground font-medium text-xs shadow-none"
                    >
                      <Sparkles className="h-3.5 w-3.5" />
                      Preview Migration
                    </Button>
                    <span className="text-[11px] text-muted-foreground/80 italic">
                      Matching and migration preview will be enabled in Phase 15E
                    </span>
                  </div>
                </div>
              </div>
            </CardHeader>
          </Card>

          {/* Ordered Track Listing Inspection */}
          <div className="space-y-4">
            <PageHeader
              title="Track Inspection"
              description="Review source tracks in their original playlist order before migration."
            />

            <TrackTable tracks={tracks} />
          </div>
        </div>
      ) : (
        /* Playlist Not Found */
        <EmptyState
          icon={ListMusic}
          title="Playlist Not Found"
          description={`Unable to locate playlist with ID "${id}". It may have been deleted or moved.`}
          action={
            <Link to="/playlists">
              <Button variant="outline" size="sm">
                Back to All Playlists
              </Button>
            </Link>
          }
        />
      )}
    </div>
  );
}
