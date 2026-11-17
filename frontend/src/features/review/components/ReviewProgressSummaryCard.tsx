import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  CheckCircle2,
  AlertCircle,
  HelpCircle,
  Ban,
  Play,
  Loader2,
  ArrowRight,
} from 'lucide-react';
import { ReviewProgressSummary, ReviewSessionStatus } from '../types';

export interface ReviewProgressSummaryCardProps {
  progress: ReviewProgressSummary;
  sessionStatus: ReviewSessionStatus;
  isExecuting?: boolean;
  onProceedToExecution: () => void;
}

export function ReviewProgressSummaryCard({
  progress,
  sessionStatus,
  isExecuting = false,
  onProceedToExecution,
}: ReviewProgressSummaryCardProps) {
  const isCompleted = sessionStatus === 'COMPLETED';
  const isEligible = progress.eligibleForCompletion || isCompleted;
  const remaining = Number(progress.remainingRequiredDecisions ?? (progress as unknown as { awaitingReviewCount?: number }).awaitingReviewCount ?? 0);
  const conflicts = Number(progress.unresolvedConflictCount ?? 0);
  const total = Number(progress.totalTracks ?? 0);
  const resolvedCount = (progress as unknown as { resolvedCount?: number }).resolvedCount !== undefined
    ? Number((progress as unknown as { resolvedCount?: number }).resolvedCount)
    : Math.max(0, total - remaining - conflicts);
  const rawPercent = total > 0 ? Math.round((resolvedCount / total) * 100) : 0;
  const progressPercent = Number.isFinite(rawPercent) ? Math.min(100, Math.max(0, rawPercent)) : 0;

  return (
    <Card className="border-border/80 bg-card shadow-sm overflow-hidden">
      <CardHeader className="p-5 pb-3">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div>
            <CardTitle className="text-base font-semibold text-foreground flex items-center gap-2">
              Review Progress & Execution Readiness
              {isEligible ? (
                <Badge variant="success" size="sm" className="gap-1">
                  <CheckCircle2 className="h-3 w-3" /> Ready for Migration
                </Badge>
              ) : (
                <Badge variant="warning" size="sm" className="gap-1">
                  <AlertCircle className="h-3 w-3" />
                  {progress.remainingRequiredDecisions} Decisions Needed
                </Badge>
              )}
            </CardTitle>
            <p className="text-xs text-muted-foreground mt-0.5">
              Review and approve match candidates before dispatching migration to YouTube Music.
            </p>
          </div>

          <Button
            size="sm"
            disabled={!isEligible || isExecuting}
            onClick={onProceedToExecution}
            className={`gap-2 h-9 px-4 text-xs font-semibold shrink-0 ${
              isEligible
                ? 'bg-primary hover:bg-primary/90 text-primary-foreground shadow-md'
                : 'opacity-60 cursor-not-allowed'
            }`}
          >
            {isExecuting ? (
              <>
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
                Submitting Migration...
              </>
            ) : isCompleted ? (
              <>
                <Play className="h-3.5 w-3.5" />
                Execute Migration <ArrowRight className="h-3.5 w-3.5" />
              </>
            ) : (
              <>
                <CheckCircle2 className="h-3.5 w-3.5" />
                Finalize & Execute <ArrowRight className="h-3.5 w-3.5" />
              </>
            )}
          </Button>
        </div>
      </CardHeader>

      <CardContent className="p-5 pt-1 space-y-4">
        {/* Progress bar */}
        <div className="space-y-1.5">
          <div className="flex justify-between text-xs font-medium text-muted-foreground">
            <span>
              Resolution Progress: <strong>{progressPercent}%</strong>
            </span>
            <span>
              {progress.totalTracks - progress.remainingRequiredDecisions} of {progress.totalTracks} tracks ready
            </span>
          </div>
          <div
            role="progressbar"
            aria-valuenow={progressPercent}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label="Track review resolution progress"
            className="h-2 w-full bg-secondary rounded-full overflow-hidden"
          >
            <div
              className={`h-full transition-all duration-300 rounded-full ${
                isEligible ? 'bg-emerald-500' : 'bg-primary'
              }`}
              style={{ width: `${progressPercent}%` }}
            />
          </div>
        </div>

        {/* Counter Pills */}
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <Badge variant="outline" size="sm" className="gap-1.5">
            Total Tracks: <strong className="text-foreground">{progress.totalTracks}</strong>
          </Badge>
          <Badge
            variant={progress.awaitingReviewCount > 0 ? 'warning' : 'outline'}
            size="sm"
            className="gap-1.5"
          >
            <HelpCircle className="h-3 w-3" />
            Needs Review: <strong>{progress.awaitingReviewCount}</strong>
          </Badge>
          <Badge variant="outline" size="sm" className="gap-1.5 text-emerald-400">
            <CheckCircle2 className="h-3 w-3" />
            Accepted: <strong>{progress.acceptedCount}</strong>
          </Badge>
          <Badge variant="outline" size="sm" className="gap-1.5 text-primary">
            Corrected: <strong>{progress.manuallyCorrectedCount}</strong>
          </Badge>
          {progress.excludedCount > 0 && (
            <Badge variant="secondary" size="sm" className="gap-1.5 text-muted-foreground">
              <Ban className="h-3 w-3" />
              Excluded: <strong>{progress.excludedCount}</strong>
            </Badge>
          )}
          {progress.unresolvedConflictCount > 0 && (
            <Badge variant="destructive" size="sm" className="gap-1.5">
              <AlertCircle className="h-3 w-3" />
              Conflicts: <strong>{progress.unresolvedConflictCount}</strong>
            </Badge>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
