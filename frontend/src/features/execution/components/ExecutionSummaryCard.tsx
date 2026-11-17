import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { CheckCircle2, AlertCircle, ExternalLink, PlaySquare, ArrowLeft } from 'lucide-react';
import { Link } from 'react-router-dom';
import { MigrationExecutionDetail } from '../types';

export interface ExecutionSummaryCardProps {
  execution: MigrationExecutionDetail;
}

export function ExecutionSummaryCard({ execution }: ExecutionSummaryCardProps) {
  const isCompleted = execution.status === 'COMPLETED';
  const isPartial = execution.status === 'PARTIALLY_COMPLETED';
  const isFailed = execution.status === 'FAILED';

  if (!isCompleted && !isPartial && !isFailed) {
    return null;
  }

  return (
    <Card
      className={`border overflow-hidden shadow-sm ${
        isCompleted
          ? 'border-emerald-500/40 bg-emerald-500/10'
          : isPartial
          ? 'border-amber-500/40 bg-amber-500/10'
          : 'border-destructive/40 bg-destructive/10'
      }`}
    >
      <CardHeader className="p-5 pb-3">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="flex items-start gap-3">
            {isCompleted ? (
              <CheckCircle2 className="h-6 w-6 text-emerald-400 shrink-0 mt-0.5" />
            ) : isPartial ? (
              <AlertCircle className="h-6 w-6 text-amber-400 shrink-0 mt-0.5" />
            ) : (
              <AlertCircle className="h-6 w-6 text-destructive shrink-0 mt-0.5" />
            )}
            <div>
              <CardTitle className="text-base font-bold text-foreground">
                {isCompleted
                  ? 'Migration Completed Successfully!'
                  : isPartial
                  ? 'Migration Partially Completed'
                  : 'Migration Execution Failed'}
              </CardTitle>
              <p className="text-xs text-muted-foreground mt-0.5">
                {isCompleted
                  ? `All ${execution.successfulInsertions} tracks have been created in your destination YouTube playlist.`
                  : isPartial
                  ? `${execution.successfulInsertions} of ${execution.totalApprovedTracks} tracks were inserted. ${execution.failedInsertions} failed, ${execution.skippedInsertions} skipped.`
                  : execution.failureSummary || 'The background migration process encountered a critical error.'}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            {execution.destinationPlaylistUrl && (
              <a
                href={execution.destinationPlaylistUrl}
                target="_blank"
                rel="noreferrer noopener"
              >
                <Button size="sm" className="gap-2 bg-youtube hover:bg-youtube/90 text-white font-semibold text-xs shadow-md">
                  <PlaySquare className="h-4 w-4" /> Open in YouTube Music <ExternalLink className="h-3.5 w-3.5" />
                </Button>
              </a>
            )}

            <Link to="/playlists">
              <Button variant="outline" size="sm" className="text-xs gap-1.5">
                <ArrowLeft className="h-3.5 w-3.5" /> Migrate Another Playlist
              </Button>
            </Link>
          </div>
        </div>
      </CardHeader>

      {execution.failureSummary && (
        <CardContent className="px-5 pb-4 pt-1">
          <div className="rounded bg-background/50 border border-border/50 p-2.5 text-xs text-muted-foreground">
            <strong className="text-foreground">Backend Execution Notes: </strong>
            {execution.failureSummary}
          </div>
        </CardContent>
      )}
    </Card>
  );
}
