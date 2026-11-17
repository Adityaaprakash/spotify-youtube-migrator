import { useParams, Link } from 'react-router-dom';
import { PageHeader } from '@/components/shared/PageHeader';
import { ErrorState } from '@/components/shared/ErrorState';
import { EmptyState } from '@/components/shared/EmptyState';
import { PageLoadingSkeleton } from '@/components/shared/LoadingSkeleton';
import { Button } from '@/components/ui/button';
import { ApiException } from '@/lib/api/types';
import { ArrowLeft, PlaySquare, AlertCircle } from 'lucide-react';
import {
  useMigrationProgress,
  useMigrationExecutionDetail,
  useMigrationExecutionTracks,
  LiveProgressBar,
  ExecutionTrackTable,
  ExecutionSummaryCard,
  isTerminalExecutionStatus,
} from '@/features/execution';

export function MigrationProgressPage() {
  const { id } = useParams<{ id: string }>();

  const {
    data: progress,
    isLoading: isLoadingProgress,
    isError: isErrorProgress,
    error: errorProgress,
    refetch: refetchProgress,
  } = useMigrationProgress(id);

  const {
    data: execution,
    isLoading: isLoadingExecution,
    refetch: refetchExecution,
  } = useMigrationExecutionDetail(id);

  const isTerminal = isTerminalExecutionStatus(progress?.status);

  const {
    data: tracks = [],
    refetch: refetchTracks,
  } = useMigrationExecutionTracks(id, isTerminal ? false : 2000);

  const isLoading = isLoadingProgress || (isLoadingExecution && !progress);

  const handleRetryAll = () => {
    void refetchProgress();
    void refetchExecution();
    void refetchTracks();
  };

  if (isLoading) {
    return (
      <div className="space-y-6">
        <PageLoadingSkeleton />
      </div>
    );
  }

  const isNotFound = errorProgress instanceof ApiException && errorProgress.status === 404;

  if (isNotFound) {
    return (
      <div className="space-y-6">
        <Link to="/playlists">
          <Button variant="ghost" size="sm" className="gap-1.5 text-xs text-muted-foreground">
            <ArrowLeft className="h-3.5 w-3.5" /> Back to playlists
          </Button>
        </Link>
        <EmptyState
          icon={AlertCircle}
          title="Migration Not Found"
          description="The requested migration execution was not found or you do not have permission to view it."
          action={
            <Link to="/playlists">
              <Button variant="outline" size="sm">
                View Playlists
              </Button>
            </Link>
          }
        />
      </div>
    );
  }

  if (isErrorProgress || !progress) {
    return (
      <div className="space-y-6">
        <Link to="/playlists">
          <Button variant="ghost" size="sm" className="gap-1.5 text-xs text-muted-foreground">
            <ArrowLeft className="h-3.5 w-3.5" /> Back to playlists
          </Button>
        </Link>
        <ErrorState
          error={errorProgress}
          title="Failed to Load Migration Progress"
          onRetry={handleRetryAll}
        />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <Link to="/playlists">
          <Button variant="ghost" size="sm" className="gap-1.5 text-xs text-muted-foreground hover:text-foreground">
            <ArrowLeft className="h-3.5 w-3.5" /> Back to playlists
          </Button>
        </Link>

        {execution?.destinationPlaylistUrl && (
          <a
            href={execution.destinationPlaylistUrl}
            target="_blank"
            rel="noreferrer noopener"
            className="text-xs font-medium text-youtube hover:underline flex items-center gap-1.5"
          >
            <PlaySquare className="h-3.5 w-3.5" /> Open Playlist in YouTube Music
          </a>
        )}
      </div>

      <PageHeader
        title={
          execution?.sourcePlaylistTitle
            ? `Migrating: ${execution.sourcePlaylistTitle}`
            : 'Migration Execution'
        }
        description={`Tracking real-time asynchronous execution (ID: ${id})`}
      />

      {/* Live Progress Bar with real-time status & counters */}
      <LiveProgressBar progress={progress} />

      {/* Terminal Outcome Summary Card (Success, Partial, Failure) */}
      {execution && <ExecutionSummaryCard execution={execution} />}

      {/* Track Outcomes Table */}
      <div className="space-y-4 pt-2">
        <div className="flex items-center justify-between border-b border-border/50 pb-2">
          <h4 className="text-sm font-semibold text-foreground">
            Track Execution Records ({tracks.length})
          </h4>
          <span className="text-xs text-muted-foreground">
            {isTerminal ? 'Final Track Outcomes' : 'Updating in real-time...'}
          </span>
        </div>

        <ExecutionTrackTable tracks={tracks} />
      </div>
    </div>
  );
}
