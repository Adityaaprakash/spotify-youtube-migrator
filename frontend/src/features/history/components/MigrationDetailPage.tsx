import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { PageHeader } from '@/components/shared/PageHeader';
import { PageLoadingSkeleton } from '@/components/shared/LoadingSkeleton';
import { ErrorState } from '@/components/shared/ErrorState';
import { EmptyState } from '@/components/shared/EmptyState';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { ApiException } from '@/lib/api/types';
import {
  ArrowLeft,
  ExternalLink,
  PlaySquare,
  Play,
  History,
  ListMusic,
  AlertCircle,
  AlertTriangle,
  Clock,
  Calendar,
} from 'lucide-react';
import { useMigrationHistoryDetail } from '../hooks/useMigrationHistoryDetail';
import { useMigrationExecutionTracks, ExecutionTrackTable } from '@/features/execution';
import { MigrationStatusBadge } from './MigrationStatusBadge';
import { MigrationAuditTimeline } from './MigrationAuditTimeline';
import { ResumeMigrationDialog } from './ResumeMigrationDialog';

function formatTimestamp(isoString?: string | null): string {
  if (!isoString) return '—';
  try {
    const d = new Date(isoString);
    return d.toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return isoString;
  }
}

function isActiveStatus(status: string): boolean {
  return (
    status === 'RUNNING' ||
    status === 'QUEUED' ||
    status === 'INITIALIZED' ||
    status === 'ALLOCATING_DESTINATION'
  );
}

export function MigrationDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [activeTab, setActiveTab] = useState<'tracks' | 'timeline'>('tracks');
  const [resumeDialogOpen, setResumeDialogOpen] = useState(false);

  const {
    data: execution,
    isLoading: isLoadingDetail,
    isError: isErrorDetail,
    error: errorDetail,
    refetch: refetchDetail,
  } = useMigrationHistoryDetail(id);

  const {
    data: tracks = [],
    isLoading: isLoadingTracks,
    refetch: refetchTracks,
  } = useMigrationExecutionTracks(id, false);

  const isLoading = isLoadingDetail;

  if (isLoading) {
    return (
      <div className="space-y-6">
        <PageLoadingSkeleton />
      </div>
    );
  }

  const isNotFound = errorDetail instanceof ApiException && errorDetail.status === 404;

  if (isNotFound) {
    return (
      <div className="space-y-6">
        <Link to="/migrations">
          <Button variant="ghost" size="sm" className="gap-1.5 text-xs text-muted-foreground">
            <ArrowLeft className="h-3.5 w-3.5" /> Back to History
          </Button>
        </Link>
        <EmptyState
          icon={AlertCircle}
          title="Migration Not Found"
          description="The requested migration execution was not found or you do not have permission to view it."
          action={
            <Link to="/migrations">
              <Button variant="outline" size="sm">
                View All Migrations
              </Button>
            </Link>
          }
        />
      </div>
    );
  }

  if (isErrorDetail || !execution) {
    return (
      <div className="space-y-6">
        <Link to="/migrations">
          <Button variant="ghost" size="sm" className="gap-1.5 text-xs text-muted-foreground">
            <ArrowLeft className="h-3.5 w-3.5" /> Back to History
          </Button>
        </Link>
        <ErrorState
          error={errorDetail}
          title="Failed to Load Migration Details"
          onRetry={() => {
            void refetchDetail();
            void refetchTracks();
          }}
        />
      </div>
    );
  }

  const active = isActiveStatus(execution.status);
  const totalTracks = execution.totalApprovedTracks || execution.totalSourceTracks;

  return (
    <div className="space-y-6">
      {/* Top Navigation */}
      <div className="flex items-center justify-between">
        <Link to="/migrations">
          <Button variant="ghost" size="sm" className="gap-1.5 text-xs text-muted-foreground hover:text-foreground">
            <ArrowLeft className="h-3.5 w-3.5" /> Back to History
          </Button>
        </Link>

        {execution.destinationPlaylistUrl && (
          <a
            href={execution.destinationPlaylistUrl}
            target="_blank"
            rel="noreferrer noopener"
            className="text-xs font-semibold text-youtube hover:underline flex items-center gap-1.5"
          >
            <PlaySquare className="h-3.5 w-3.5" /> Open Playlist in YouTube Music
          </a>
        )}
      </div>

      {/* Active In-Progress Banner */}
      {active && (
        <div className="rounded-lg border border-blue-500/40 bg-blue-500/10 p-4 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 shadow-sm">
          <div className="flex items-center gap-2.5 text-blue-400">
            <Clock className="h-5 w-5 shrink-0 animate-pulse" />
            <div>
              <div className="text-xs font-bold text-foreground">Active Background Migration</div>
              <div className="text-xs text-muted-foreground">
                This migration is currently executing. Track insertions and status are actively updating.
              </div>
            </div>
          </div>

          <Link to={`/migrations/${execution.executionId}/progress`}>
            <Button size="sm" className="gap-1.5 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold shrink-0">
              <PlaySquare className="h-3.5 w-3.5" />
              Watch Live Progress
            </Button>
          </Link>
        </div>
      )}

      {/* Hero Header Card */}
      <Card className="border-border/80 bg-card/60 shadow-sm overflow-hidden">
        <CardHeader className="p-6 pb-4">
          <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
            <div className="space-y-2">
              <div className="flex flex-wrap items-center gap-2">
                <MigrationStatusBadge status={execution.status} />
                <Badge variant="secondary" size="sm" className="font-mono text-[11px]">
                  ID: {execution.executionId.slice(0, 8)}...
                </Badge>
                {execution.destinationPlatform && (
                  <Badge variant="outline" size="sm" className="text-[11px]">
                    Destination: {execution.destinationPlatform}
                  </Badge>
                )}
              </div>

              <CardTitle className="text-2xl font-bold tracking-tight text-foreground">
                {execution.sourcePlaylistTitle || 'Untitled Playlist'}
              </CardTitle>

              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
                <span className="flex items-center gap-1">
                  <Calendar className="h-3.5 w-3.5" />
                  Started: {formatTimestamp(execution.startedAt || execution.createdAt)}
                </span>
                {execution.completedAt && (
                  <span className="flex items-center gap-1">
                    <Clock className="h-3.5 w-3.5" />
                    Completed: {formatTimestamp(execution.completedAt)}
                  </span>
                )}
              </div>
            </div>

            {/* Resume Action if Eligible */}
            <div className="flex flex-wrap items-center gap-2 shrink-0">
              {execution.canResume ? (
                <Button
                  type="button"
                  size="sm"
                  onClick={() => setResumeDialogOpen(true)}
                  aria-label={`Resume migration for ${execution.sourcePlaylistTitle || 'playlist'}`}
                  className="gap-2 bg-primary hover:bg-primary/90 text-primary-foreground font-semibold text-xs shadow-md"
                >
                  <Play className="h-3.5 w-3.5" aria-hidden="true" />
                  Resume Migration
                </Button>
              ) : active ? (
                <Link to={`/migrations/${execution.executionId}/progress`}>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="gap-1.5 text-xs"
                    aria-label={`Watch live progress for ${execution.sourcePlaylistTitle || 'migration'}`}
                  >
                    <PlaySquare className="h-3.5 w-3.5" aria-hidden="true" />
                    Live Progress
                  </Button>
                </Link>
              ) : null}

              {execution.destinationPlaylistUrl && (
                <a
                  href={execution.destinationPlaylistUrl}
                  target="_blank"
                  rel="noreferrer noopener"
                  aria-label={`Open destination playlist for ${execution.sourcePlaylistTitle || 'migration'} on YouTube Music`}
                >
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="gap-1.5 text-xs text-youtube border-youtube/30 hover:bg-youtube/10"
                  >
                    <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
                    YouTube Music
                  </Button>
                </a>
              )}
            </div>
          </div>
        </CardHeader>

        {/* Aggregate Track Metrics Grid */}
        <CardContent className="p-6 pt-2">
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3 pt-3 border-t border-border/60">
            <div className="rounded-md bg-secondary/30 p-2.5 text-center">
              <span className="text-[11px] text-muted-foreground block font-medium">Source Tracks</span>
              <strong className="text-base text-foreground font-semibold">{execution.totalSourceTracks}</strong>
            </div>

            <div className="rounded-md bg-secondary/30 p-2.5 text-center">
              <span className="text-[11px] text-muted-foreground block font-medium">Approved</span>
              <strong className="text-base text-foreground font-semibold">{totalTracks}</strong>
            </div>

            <div className="rounded-md bg-emerald-500/10 p-2.5 text-center border border-emerald-500/20">
              <span className="text-[11px] text-emerald-400 block font-medium">Added</span>
              <strong className="text-base text-emerald-400 font-semibold">{execution.successfulInsertions}</strong>
            </div>

            <div className="rounded-md bg-destructive/10 p-2.5 text-center border border-destructive/20">
              <span className="text-[11px] text-destructive block font-medium">Failed</span>
              <strong className="text-base text-destructive font-semibold">{execution.failedInsertions}</strong>
            </div>

            <div className="rounded-md bg-secondary/30 p-2.5 text-center">
              <span className="text-[11px] text-muted-foreground block font-medium">Skipped</span>
              <strong className="text-base text-muted-foreground font-semibold">{execution.skippedInsertions}</strong>
            </div>

            <div className="rounded-md bg-amber-500/10 p-2.5 text-center border border-amber-500/20">
              <span className="text-[11px] text-amber-400 block font-medium">Uncertain</span>
              <strong className="text-base text-amber-400 font-semibold">{execution.uncertainInsertions}</strong>
            </div>
          </div>

          {/* Failure Summary Warning Banner */}
          {execution.failureSummary && (
            <div className="mt-4 rounded-md border border-destructive/40 bg-destructive/10 p-3 flex items-start gap-2 text-destructive text-xs">
              <AlertTriangle className="h-4 w-4 shrink-0 mt-0.5" />
              <div>
                <strong>Failure Diagnostic:</strong> {execution.failureSummary}
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Tabs Navigation (Track Outcomes vs. Audit Timeline) */}
      <div className="space-y-4">
        <div className="flex items-center gap-2 border-b border-border/60 pb-2">
          <Button
            variant={activeTab === 'tracks' ? 'default' : 'ghost'}
            size="sm"
            onClick={() => setActiveTab('tracks')}
            className={`gap-1.5 text-xs font-semibold ${
              activeTab === 'tracks'
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            <ListMusic className="h-3.5 w-3.5" />
            Track Execution Records ({tracks.length})
          </Button>

          <Button
            variant={activeTab === 'timeline' ? 'default' : 'ghost'}
            size="sm"
            onClick={() => setActiveTab('timeline')}
            className={`gap-1.5 text-xs font-semibold ${
              activeTab === 'timeline'
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            <History className="h-3.5 w-3.5" />
            Audit Event Timeline
          </Button>
        </div>

        {/* Tab 1: Track Execution Records */}
        {activeTab === 'tracks' && (
          <div className="space-y-3">
            <PageHeader
              title="Itemized Track Outcomes"
              description="Per-track execution results preserving canonical target position order and match evidence."
            />
            {isLoadingTracks ? (
              <div className="rounded-lg border border-border/60 p-4">
                <PageLoadingSkeleton />
              </div>
            ) : (
              <ExecutionTrackTable tracks={tracks} />
            )}
          </div>
        )}

        {/* Tab 2: Audit Event Timeline */}
        {activeTab === 'timeline' && (
          <div className="space-y-3">
            <PageHeader
              title="Chronological Audit Timeline"
              description="Authoritative backend state transitions and system lifecycle events recorded for this migration."
            />
            <MigrationAuditTimeline executionId={execution.executionId} />
          </div>
        )}
      </div>

      {/* Resume Confirmation Dialog */}
      <ResumeMigrationDialog
        open={resumeDialogOpen}
        onOpenChange={setResumeDialogOpen}
        execution={execution}
      />
    </div>
  );
}
