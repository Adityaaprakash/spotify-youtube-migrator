import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { MigrationExecutionDetail } from '@/features/execution/types';
import { useResumeMigration } from '../hooks/useResumeMigration';
import { Play, Loader2, AlertCircle, CheckCircle2, ShieldCheck } from 'lucide-react';

export interface ResumeMigrationDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  execution: MigrationExecutionDetail;
}

export function ResumeMigrationDialog({
  open,
  onOpenChange,
  execution,
}: ResumeMigrationDialogProps) {
  const navigate = useNavigate();
  const { mutateAsync: resumeMigration, isPending } = useResumeMigration();
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const remainingTracks = Math.max(
    0,
    (execution.totalApprovedTracks || execution.totalSourceTracks) - execution.successfulInsertions
  );

  const handleConfirm = async () => {
    setErrorMessage(null);
    try {
      const updated = await resumeMigration(execution.executionId);
      onOpenChange(false);
      // Navigate directly to live progress monitoring
      navigate(`/migrations/${updated.executionId}/progress`);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to resume migration execution.';
      setErrorMessage(msg);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(val) => !isPending && onOpenChange(val)}>
      <DialogContent onClose={() => !isPending && onOpenChange(false)} className="max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-foreground">
            <Play className="h-5 w-5 text-primary" />
            Resume Migration Execution
          </DialogTitle>
          <DialogDescription>
            Resume background processing for unfinished tracks in this migration.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-2 text-xs">
          {/* Migration Metadata Card */}
          <div className="rounded-md border border-border/70 bg-secondary/30 p-3 space-y-2">
            <div className="flex justify-between items-center text-foreground">
              <span className="text-muted-foreground font-medium">Playlist:</span>
              <strong className="font-semibold">{execution.sourcePlaylistTitle || 'Untitled Playlist'}</strong>
            </div>
            <div className="flex justify-between items-center text-foreground">
              <span className="text-muted-foreground font-medium">Already Added:</span>
              <span className="text-emerald-400 font-semibold">{execution.successfulInsertions} tracks</span>
            </div>
            <div className="flex justify-between items-center text-foreground">
              <span className="text-muted-foreground font-medium">Remaining to Insert:</span>
              <span className="text-primary font-semibold">{remainingTracks} tracks</span>
            </div>
          </div>

          {/* Safety Guarantees */}
          <div className="space-y-2 text-muted-foreground">
            <div className="flex items-start gap-2">
              <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0 mt-0.5" />
              <span>Already added tracks are guaranteed to be skipped without duplicating.</span>
            </div>
            <div className="flex items-start gap-2">
              <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0 mt-0.5" />
              <span>Existing destination YouTube Music playlist will be preserved and reused.</span>
            </div>
            <div className="flex items-start gap-2">
              <ShieldCheck className="h-4 w-4 text-primary shrink-0 mt-0.5" />
              <span>Background processing resumes with fresh worker leases and retry budgets.</span>
            </div>
          </div>

          {/* Error Banner */}
          {errorMessage && (
            <div className="rounded-md border border-destructive/40 bg-destructive/10 p-3 flex items-start gap-2 text-destructive">
              <AlertCircle className="h-4 w-4 shrink-0 mt-0.5" />
              <div className="flex-1 text-xs">
                <strong>Resume Failed:</strong> {errorMessage}
              </div>
            </div>
          )}
        </div>

        <DialogFooter className="gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={isPending}
            onClick={() => onOpenChange(false)}
          >
            Cancel
          </Button>
          <Button
            size="sm"
            disabled={isPending}
            onClick={handleConfirm}
            className="gap-2 bg-primary hover:bg-primary/90 text-primary-foreground font-semibold shadow-sm"
          >
            {isPending ? (
              <>
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
                Resuming Execution...
              </>
            ) : (
              <>
                <Play className="h-3.5 w-3.5" />
                Confirm & Resume
              </>
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
