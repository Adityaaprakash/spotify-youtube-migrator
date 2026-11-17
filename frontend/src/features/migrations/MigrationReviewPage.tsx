import { useState, useMemo } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { PageHeader } from '@/components/shared/PageHeader';
import { EmptyState } from '@/components/shared/EmptyState';
import { ErrorState } from '@/components/shared/ErrorState';
import { PageLoadingSkeleton } from '@/components/shared/LoadingSkeleton';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  ArrowLeft,
  Search,
  ListMusic,
  X,
  AlertCircle,
  HelpCircle,
  CheckCircle2,
  Ban,
} from 'lucide-react';
import {
  useReviewSession,
  useReviewProgress,
  useReviewTracks,
  useReviewDecision,
  useCompleteReviewSession,
  ReviewProgressSummaryCard,
  ReviewTrackItem,
  DestinationCandidate,
} from '@/features/review';
import { useSubmitMigration } from '@/features/execution';

type FilterTab = 'all' | 'needs-review' | 'accepted' | 'corrected' | 'excluded' | 'conflicts';

export function MigrationReviewPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [activeTab, setActiveTab] = useState<FilterTab>('all');
  const [searchTerm, setSearchTerm] = useState('');
  const [submissionError, setSubmissionError] = useState<string | null>(null);

  const {
    data: session,
    isLoading: isLoadingSession,
    isError: isErrorSession,
    error: errorSession,
    refetch: refetchSession,
  } = useReviewSession(id);

  const {
    data: progress,
    isLoading: isLoadingProgress,
    refetch: refetchProgress,
  } = useReviewProgress(id);

  const {
    data: tracks = [],
    isLoading: isLoadingTracks,
    isError: isErrorTracks,
    error: errorTracks,
    refetch: refetchTracks,
  } = useReviewTracks(id);

  const { accept, select, exclude } = useReviewDecision(id ?? '');
  const completeMutation = useCompleteReviewSession(id ?? '');
  const submitMigrationMutation = useSubmitMigration();

  const isLoading = isLoadingSession || isLoadingProgress || isLoadingTracks;
  const isError = isErrorSession || isErrorTracks;
  const error = errorSession || errorTracks;

  // Track decision actions
  const handleAccept = async (position: number) => {
    setSubmissionError(null);
    await accept(position);
  };

  const handleSelectCandidate = async (position: number, candidate: DestinationCandidate) => {
    setSubmissionError(null);
    await select({
      position,
      request: {
        candidate: {
          id: candidate.id,
          title: candidate.title,
          channelOrArtist: candidate.channelOrArtist,
          durationMs: candidate.durationMs,
        },
      },
    });
  };

  const handleExclude = async (position: number, reason?: string) => {
    setSubmissionError(null);
    await exclude({ position, reason });
  };

  // Execution dispatch workflow
  const handleProceedToExecution = async () => {
    if (!id || !session) return;
    setSubmissionError(null);

    try {
      // Step 1: Ensure review session is finalized/completed in backend
      if (session.status === 'IN_PROGRESS') {
        await completeMutation.mutateAsync();
      }

      // Step 2: Submit approved migration execution
      const submission = await submitMigrationMutation.mutateAsync({
        request: {
          reviewSessionId: id,
          sourcePlaylistId: session.sourcePlaylistId,
        },
      });

      // Step 3: Navigate to live progress page
      navigate(`/migrations/${submission.executionId}/progress`);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to submit migration execution.';
      setSubmissionError(msg);
    }
  };

  // Filter and search logic
  const filteredTracks = useMemo(() => {
    let result = tracks;

    switch (activeTab) {
      case 'needs-review':
        result = result.filter((t) => t.reviewStatus === 'AWAITING_REVIEW' && !t.excluded);
        break;
      case 'accepted':
        result = result.filter((t) => t.reviewStatus === 'ACCEPTED' && !t.excluded);
        break;
      case 'corrected':
        result = result.filter((t) => t.reviewStatus === 'USER_SELECTED' && !t.excluded);
        break;
      case 'excluded':
        result = result.filter((t) => t.excluded);
        break;
      case 'conflicts':
        result = result.filter((t) => t.hasConflict);
        break;
      default:
        break;
    }

    if (searchTerm.trim()) {
      const q = searchTerm.toLowerCase().trim();
      result = result.filter(
        (t) =>
          t.sourceTitle.toLowerCase().includes(q) ||
          t.sourceArtist.toLowerCase().includes(q) ||
          (t.selectedCandidate && t.selectedCandidate.title.toLowerCase().includes(q)) ||
          (t.originalCandidate && t.originalCandidate.title.toLowerCase().includes(q))
      );
    }

    return result;
  }, [tracks, activeTab, searchTerm]);

  if (isLoading) {
    return (
      <div className="space-y-6">
        <PageLoadingSkeleton />
      </div>
    );
  }

  if (isError || !session) {
    return (
      <div className="space-y-6">
        <Link to="/playlists">
          <Button variant="ghost" size="sm" className="gap-1.5 text-xs text-muted-foreground">
            <ArrowLeft className="h-3.5 w-3.5" /> Back to playlists
          </Button>
        </Link>
        <ErrorState
          error={error}
          title="Failed to Load Review Session"
          onRetry={() => {
            void refetchSession();
            void refetchProgress();
            void refetchTracks();
          }}
        />
      </div>
    );
  }

  if (session.status === 'CANCELLED') {
    return (
      <div className="space-y-6">
        <Link to="/playlists">
          <Button variant="ghost" size="sm" className="gap-1.5 text-xs text-muted-foreground">
            <ArrowLeft className="h-3.5 w-3.5" /> Back to playlists
          </Button>
        </Link>
        <EmptyState
          icon={AlertCircle}
          title="Review Session Cancelled"
          description="This review session was cancelled or abandoned. You can start a fresh migration from your playlists."
          action={
            <Link to="/playlists">
              <Button size="sm">Browse Playlists</Button>
            </Link>
          }
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
      </div>

      <PageHeader
        title={`Review Matches: ${session.sourcePlaylistTitle}`}
        description="Inspect and adjust automatic match candidates before launching your migration to YouTube Music."
      />

      {submissionError && (
        <ErrorState
          error={submissionError}
          title="Submission Error"
          onRetry={handleProceedToExecution}
        />
      )}

      {/* Progress & Readiness Hero Card */}
      {progress && (
        <ReviewProgressSummaryCard
          progress={progress}
          sessionStatus={session.status}
          isExecuting={completeMutation.isPending || submitMigrationMutation.isPending}
          onProceedToExecution={handleProceedToExecution}
        />
      )}

      {/* Track Inspection & Filtering Header */}
      <div className="space-y-4">
        <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 border-b border-border/50 pb-3">
          {/* Tab buttons */}
          <div className="flex flex-wrap items-center gap-1.5">
            <Button
              variant={activeTab === 'all' ? 'default' : 'ghost'}
              size="sm"
              onClick={() => setActiveTab('all')}
              className="h-8 text-xs font-medium"
            >
              All ({tracks.length})
            </Button>
            <Button
              variant={activeTab === 'needs-review' ? 'default' : 'ghost'}
              size="sm"
              onClick={() => setActiveTab('needs-review')}
              className="h-8 text-xs font-medium gap-1 text-amber-400"
            >
              <HelpCircle className="h-3 w-3" />
              Needs Review ({progress?.awaitingReviewCount ?? 0})
            </Button>
            <Button
              variant={activeTab === 'accepted' ? 'default' : 'ghost'}
              size="sm"
              onClick={() => setActiveTab('accepted')}
              className="h-8 text-xs font-medium gap-1 text-emerald-400"
            >
              <CheckCircle2 className="h-3 w-3" />
              Accepted ({progress?.acceptedCount ?? 0})
            </Button>
            <Button
              variant={activeTab === 'corrected' ? 'default' : 'ghost'}
              size="sm"
              onClick={() => setActiveTab('corrected')}
              className="h-8 text-xs font-medium"
            >
              Corrected ({progress?.manuallyCorrectedCount ?? 0})
            </Button>
            <Button
              variant={activeTab === 'excluded' ? 'default' : 'ghost'}
              size="sm"
              onClick={() => setActiveTab('excluded')}
              className="h-8 text-xs font-medium gap-1 text-muted-foreground"
            >
              <Ban className="h-3 w-3" />
              Excluded ({progress?.excludedCount ?? 0})
            </Button>
            {(progress?.unresolvedConflictCount ?? 0) > 0 && (
              <Button
                variant={activeTab === 'conflicts' ? 'destructive' : 'ghost'}
                size="sm"
                onClick={() => setActiveTab('conflicts')}
                className="h-8 text-xs font-medium gap-1 text-rose-400"
              >
                <AlertCircle className="h-3 w-3" />
                Conflicts ({progress?.unresolvedConflictCount})
              </Button>
            )}
          </div>

          {/* Search box */}
          <div className="relative w-full sm:w-64">
            <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-muted-foreground pointer-events-none" aria-hidden="true" />
            <Input
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="Search tracks in review..."
              aria-label="Search tracks in review"
              className="pl-8 pr-8 h-8 text-xs"
            />
            {searchTerm && (
              <button
                type="button"
                onClick={() => setSearchTerm('')}
                className="absolute right-2.5 top-2 text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring rounded"
                aria-label="Clear search"
              >
                <X className="h-3.5 w-3.5" aria-hidden="true" />
              </button>
            )}
          </div>
        </div>

        {/* Tracks List */}
        {filteredTracks.length === 0 ? (
          <EmptyState
            icon={ListMusic}
            title={searchTerm ? 'No tracks match your search' : 'No tracks in this view'}
            description={
              searchTerm
                ? 'Try adjusting your search query or clear the filter.'
                : 'All tracks in this category have been addressed.'
            }
          />
        ) : (
          <div className="space-y-3">
            {filteredTracks.map((track) => (
              <ReviewTrackItem
                key={track.id}
                track={track}
                sessionId={id ?? ''}
                onAccept={handleAccept}
                onSelect={handleSelectCandidate}
                onExclude={handleExclude}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
