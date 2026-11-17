import { useEffect } from 'react';
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
import { Badge } from '@/components/ui/badge';
import {
  Sparkles,
  Loader2,
  CheckCircle2,
  AlertCircle,
  HelpCircle,
  Copy,
  ArrowRight,
} from 'lucide-react';
import { useMigrationPreview } from '../hooks/useMigrationPreview';
import { useCreateReviewSession } from '../hooks/useCreateReviewSession';

export interface MigrationPreviewModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  playlistId: string;
  playlistName: string;
}

export function MigrationPreviewModal({
  open,
  onOpenChange,
  playlistId,
  playlistName,
}: MigrationPreviewModalProps) {
  const navigate = useNavigate();
  const previewMutation = useMigrationPreview();
  const createSessionMutation = useCreateReviewSession();

  useEffect(() => {
    if (open && playlistId && !previewMutation.data && !previewMutation.isPending) {
      previewMutation.mutate({ spotifyPlaylistId: playlistId, deduplicate: true });
    }
  }, [open, playlistId, previewMutation]);

  const handleStartReview = async () => {
    if (!playlistId) return;
    const planId = previewMutation.data?.planId;
    const session = await createSessionMutation.mutateAsync({
      sourcePlaylistId: playlistId,
      planId,
      deduplicate: true,
    });
    onOpenChange(false);
    navigate(`/migrations/${session.id}/review`);
  };

  const plan = previewMutation.data;
  const summary = plan?.summary;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[85vh] flex flex-col p-6">
        <DialogHeader>
          <DialogTitle className="text-lg flex items-center gap-2">
            <Sparkles className="h-5 w-5 text-primary" />
            Migration Plan Preview
          </DialogTitle>
          <DialogDescription className="text-xs">
            Point-in-time match analysis for:{' '}
            <strong className="text-foreground">{playlistName}</strong>
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 overflow-y-auto pr-1 my-2 min-h-[280px]">
          {previewMutation.isPending ? (
            <div className="flex flex-col items-center justify-center py-16 text-muted-foreground space-y-3">
              <Loader2 className="h-8 w-8 animate-spin text-primary" />
              <p className="text-sm font-medium">Analyzing tracks & matching candidates...</p>
              <p className="text-xs text-muted-foreground max-w-sm text-center">
                Querying YouTube Music candidates and calculating match probabilities without making any changes.
              </p>
            </div>
          ) : previewMutation.isError ? (
            <div className="py-10 text-center text-xs text-destructive bg-destructive/10 rounded-md p-4 space-y-2">
              <p className="font-semibold">Failed to generate migration preview</p>
              <p>{previewMutation.error.message}</p>
              <Button
                variant="outline"
                size="sm"
                onClick={() => previewMutation.mutate({ spotifyPlaylistId: playlistId, deduplicate: true })}
                className="mt-2 text-xs"
              >
                Retry Preview
              </Button>
            </div>
          ) : summary ? (
            <div className="space-y-5">
              {/* Summary Stats Grid */}
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <div className="rounded-lg border border-border/80 bg-secondary/30 p-3 text-center">
                  <div className="text-xs text-muted-foreground font-medium">Total Tracks</div>
                  <div className="text-xl font-bold text-foreground mt-0.5">{summary.totalTracks}</div>
                </div>

                <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/10 p-3 text-center">
                  <div className="text-xs text-emerald-400 font-medium flex items-center justify-center gap-1">
                    <CheckCircle2 className="h-3.5 w-3.5" /> Auto-Matched
                  </div>
                  <div className="text-xl font-bold text-emerald-400 mt-0.5">
                    {summary.autoMatchCount}
                  </div>
                  <div className="text-[10px] text-muted-foreground">
                    {Math.round(summary.autoMatchRate * 100)}% confidence
                  </div>
                </div>

                <div className="rounded-lg border border-amber-500/30 bg-amber-500/10 p-3 text-center">
                  <div className="text-xs text-amber-400 font-medium flex items-center justify-center gap-1">
                    <HelpCircle className="h-3.5 w-3.5" /> Needs Review
                  </div>
                  <div className="text-xl font-bold text-amber-400 mt-0.5">
                    {summary.reviewRequiredCount}
                  </div>
                  <div className="text-[10px] text-muted-foreground">
                    {Math.round(summary.reviewRate * 100)}% ambiguous
                  </div>
                </div>

                <div className="rounded-lg border border-border/80 bg-secondary/30 p-3 text-center">
                  <div className="text-xs text-muted-foreground font-medium flex items-center justify-center gap-1">
                    <AlertCircle className="h-3.5 w-3.5" /> Unmatched
                  </div>
                  <div className="text-xl font-bold text-foreground mt-0.5">
                    {summary.unmatchedCount}
                  </div>
                  <div className="text-[10px] text-muted-foreground">
                    {summary.duplicateCount > 0 ? `${summary.duplicateCount} duplicates` : '0 duplicates'}
                  </div>
                </div>
              </div>

              {/* Sample Track breakdown preview */}
              <div className="space-y-2">
                <div className="flex items-center justify-between text-xs text-muted-foreground font-medium">
                  <span>Track Breakdown ({plan.tracks.length} tracks evaluated)</span>
                  <span>Source Playlist Order</span>
                </div>

                <div className="rounded-lg border border-border/70 divide-y divide-border/40 overflow-hidden bg-card text-xs max-h-60 overflow-y-auto">
                  {plan.tracks.map((track) => (
                    <div key={track.position} className="p-2.5 flex items-center justify-between gap-2 hover:bg-secondary/20">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="font-mono text-[10px] text-muted-foreground w-6 text-center">
                          #{track.position + 1}
                        </span>
                        <div className="min-w-0 truncate">
                          <span className="font-medium text-foreground truncate">{track.sourceTitle}</span>
                          <span className="text-muted-foreground text-[11px] ml-1.5">— {track.sourceArtist}</span>
                        </div>
                      </div>

                      <div className="shrink-0 flex items-center gap-2">
                        {track.classification === 'AUTO_MATCH' ? (
                          <Badge variant="success" size="sm" className="text-[10px]">
                            Auto Match
                          </Badge>
                        ) : track.classification === 'REVIEW_REQUIRED' ? (
                          <Badge variant="warning" size="sm" className="text-[10px]">
                            Review Needed
                          </Badge>
                        ) : track.classification === 'DUPLICATE' ? (
                          <Badge variant="outline" size="sm" className="text-[10px] gap-1">
                            <Copy className="h-2.5 w-2.5" /> Duplicate
                          </Badge>
                        ) : (
                          <Badge variant="secondary" size="sm" className="text-[10px]">
                            {track.classification}
                          </Badge>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          ) : null}
        </div>

        <DialogFooter className="flex items-center justify-between gap-3 pt-3 border-t border-border/40">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => onOpenChange(false)}
            className="text-xs"
          >
            Cancel
          </Button>

          <Button
            type="button"
            size="sm"
            disabled={!plan || createSessionMutation.isPending}
            onClick={handleStartReview}
            className="gap-2 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold"
          >
            {createSessionMutation.isPending ? (
              <>
                <Loader2 className="h-3.5 w-3.5 animate-spin" /> Creating Review Session...
              </>
            ) : (
              <>
                Start Review Session <ArrowRight className="h-3.5 w-3.5" />
              </>
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
