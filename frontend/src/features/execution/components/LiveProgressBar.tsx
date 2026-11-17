import { Badge } from '@/components/ui/badge';
import {
  CheckCircle2,
  AlertCircle,
  Clock,
  Loader2,
  Ban,
  HelpCircle,
} from 'lucide-react';
import { MigrationProgress, MigrationExecutionStatus } from '../types';

export interface LiveProgressBarProps {
  progress: MigrationProgress;
}

function getStatusBadge(status: MigrationExecutionStatus) {
  switch (status) {
    case 'COMPLETED':
      return (
        <Badge variant="success" size="sm" className="gap-1.5 font-semibold">
          <CheckCircle2 className="h-3.5 w-3.5" /> Completed
        </Badge>
      );
    case 'RUNNING':
      return (
        <Badge variant="default" size="sm" className="gap-1.5 font-semibold animate-pulse">
          <Loader2 className="h-3.5 w-3.5 animate-spin" /> Inserting Tracks...
        </Badge>
      );
    case 'QUEUED':
      return (
        <Badge variant="outline" size="sm" className="gap-1.5 font-semibold">
          <Clock className="h-3.5 w-3.5" /> Queued for Worker
        </Badge>
      );
    case 'ALLOCATING_DESTINATION':
    case 'INITIALIZED':
      return (
        <Badge variant="secondary" size="sm" className="gap-1.5 font-semibold">
          <Loader2 className="h-3.5 w-3.5 animate-spin" /> Creating Destination Playlist...
        </Badge>
      );
    case 'PARTIALLY_COMPLETED':
      return (
        <Badge variant="warning" size="sm" className="gap-1.5 font-semibold">
          <AlertCircle className="h-3.5 w-3.5" /> Partially Completed
        </Badge>
      );
    case 'FAILED':
      return (
        <Badge variant="destructive" size="sm" className="gap-1.5 font-semibold">
          <AlertCircle className="h-3.5 w-3.5" /> Execution Failed
        </Badge>
      );
    case 'RECOVERY_REQUIRED':
      return (
        <Badge variant="destructive" size="sm" className="gap-1.5 font-semibold">
          <AlertCircle className="h-3.5 w-3.5" /> Recovery Required
        </Badge>
      );
    case 'ROLLED_BACK':
      return (
        <Badge variant="secondary" size="sm" className="gap-1.5 font-semibold">
          <Ban className="h-3.5 w-3.5" /> Rolled Back
        </Badge>
      );
    default:
      return (
        <Badge variant="outline" size="sm">
          {status}
        </Badge>
      );
  }
}

export function LiveProgressBar({ progress }: LiveProgressBarProps) {
  const percentage = Math.min(100, Math.max(0, Math.round(progress.progressPercentage * 10) / 10));
  const isComplete = progress.status === 'COMPLETED';
  const isFailed = progress.status === 'FAILED' || progress.status === 'RECOVERY_REQUIRED';
  const isPartial = progress.status === 'PARTIALLY_COMPLETED';

  return (
    <div className="space-y-4 rounded-xl border border-border/80 bg-card p-6 shadow-sm">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div className="space-y-1">
          <div className="flex items-center gap-2.5">
            <h3 className="text-lg font-bold text-foreground">Live Migration Progress</h3>
            {getStatusBadge(progress.status)}
          </div>
          <p className="text-xs text-muted-foreground">
            {isComplete
              ? 'All approved tracks have been successfully inserted into YouTube Music.'
              : isPartial
              ? 'Execution stopped with partial successes and failures.'
              : isFailed
              ? 'Migration execution halted due to errors.'
              : 'Streaming live track insertion progress from backend workers.'}
          </p>
        </div>

        <div className="text-right shrink-0">
          <span className="font-mono text-2xl font-black text-foreground">
            {percentage}%
          </span>
          <div className="text-[11px] text-muted-foreground">
            {progress.processedTracks} of {progress.totalTracks} tracks processed
          </div>
        </div>
      </div>

      {/* Progress track */}
      <div className="h-3 w-full bg-secondary rounded-full overflow-hidden p-0.5">
        <div
          className={`h-full rounded-full transition-all duration-500 ease-out ${
            isComplete
              ? 'bg-emerald-500'
              : isPartial
              ? 'bg-amber-500'
              : isFailed
              ? 'bg-rose-500'
              : 'bg-primary'
          }`}
          style={{ width: `${percentage}%` }}
        />
      </div>

      {/* Track counters grid */}
      <div className="flex flex-wrap items-center gap-2 pt-2 border-t border-border/40 text-xs">
        <Badge variant="outline" size="sm" className="gap-1.5 font-medium">
          Total: <strong className="text-foreground">{progress.totalTracks}</strong>
        </Badge>
        <Badge variant="outline" size="sm" className="gap-1.5 text-emerald-400 font-medium">
          <CheckCircle2 className="h-3 w-3" />
          Added: <strong className="text-emerald-400">{progress.addedTracks}</strong>
        </Badge>
        {progress.addingTracks > 0 && (
          <Badge variant="default" size="sm" className="gap-1.5 font-medium">
            <Loader2 className="h-3 w-3 animate-spin" />
            Adding: <strong className="text-primary">{progress.addingTracks}</strong>
          </Badge>
        )}
        {progress.pendingTracks > 0 && (
          <Badge variant="outline" size="sm" className="gap-1.5 text-muted-foreground font-medium">
            <Clock className="h-3 w-3" />
            Pending: <strong className="text-foreground">{progress.pendingTracks}</strong>
          </Badge>
        )}
        {progress.failedTracks > 0 && (
          <Badge variant="destructive" size="sm" className="gap-1.5 font-medium">
            <AlertCircle className="h-3 w-3" />
            Failed: <strong>{progress.failedTracks}</strong>
          </Badge>
        )}
        {progress.skippedTracks > 0 && (
          <Badge variant="secondary" size="sm" className="gap-1.5 text-muted-foreground font-medium">
            <Ban className="h-3 w-3" />
            Skipped: <strong>{progress.skippedTracks}</strong>
          </Badge>
        )}
        {progress.uncertainTracks > 0 && (
          <Badge variant="warning" size="sm" className="gap-1.5 font-medium">
            <HelpCircle className="h-3 w-3" />
            Uncertain: <strong>{progress.uncertainTracks}</strong>
          </Badge>
        )}
      </div>
    </div>
  );
}
