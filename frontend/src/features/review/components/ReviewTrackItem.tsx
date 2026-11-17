import { useState } from 'react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Check,
  Search,
  Ban,
  AlertTriangle,
  PlaySquare,
  Clock,
  Sparkles,
  Loader2,
  HelpCircle,
} from 'lucide-react';
import { ReviewTrack, DestinationCandidate } from '../types';
import { formatDuration } from '@/features/playlists/utils';
import { CandidateSearchDialog } from './CandidateSearchDialog';

export interface ReviewTrackItemProps {
  track: ReviewTrack;
  sessionId: string;
  onAccept: (position: number) => Promise<unknown>;
  onSelect: (position: number, candidate: DestinationCandidate) => Promise<unknown>;
  onExclude: (position: number, reason?: string) => Promise<unknown>;
}

export function ReviewTrackItem({
  track,
  sessionId,
  onAccept,
  onSelect,
  onExclude,
}: ReviewTrackItemProps) {
  const [searchOpen, setSearchOpen] = useState(false);
  const [isActionPending, setIsActionPending] = useState(false);

  const activeCandidate = track.selectedCandidate || track.originalCandidate;
  const isExcluded = track.excluded;
  const isAccepted = track.reviewStatus === 'ACCEPTED';
  const isUserSelected = track.reviewStatus === 'USER_SELECTED';

  const handleAcceptClick = async () => {
    setIsActionPending(true);
    try {
      await onAccept(track.position);
    } finally {
      setIsActionPending(false);
    }
  };

  const handleExcludeClick = async () => {
    setIsActionPending(true);
    try {
      await onExclude(track.position, 'Excluded by user during review');
    } finally {
      setIsActionPending(false);
    }
  };

  const handleSelectCandidate = async (candidate: DestinationCandidate) => {
    await onSelect(track.position, candidate);
  };

  return (
    <>
      <Card
        className={`border-border/70 overflow-hidden transition-all ${
          track.hasConflict
            ? 'border-amber-500/50 bg-amber-500/5'
            : isExcluded
            ? 'opacity-60 bg-secondary/20'
            : isAccepted || isUserSelected
            ? 'bg-card/80'
            : 'border-primary/40 bg-card'
        }`}
      >
        <CardHeader className="p-4 pb-2">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
            <div className="flex items-center gap-2.5">
              <span className="font-mono text-xs font-semibold text-muted-foreground bg-secondary px-2 py-0.5 rounded">
                #{track.position + 1}
              </span>
              <CardTitle className="text-sm font-semibold text-foreground truncate max-w-sm sm:max-w-md">
                {track.sourceTitle}
              </CardTitle>
            </div>

            <div className="flex items-center gap-2 shrink-0">
              {isExcluded ? (
                <Badge variant="secondary" size="sm" className="gap-1">
                  <Ban className="h-3 w-3" /> Excluded
                </Badge>
              ) : isUserSelected ? (
                <Badge variant="success" size="sm" className="gap-1">
                  <Check className="h-3 w-3" /> Manually Corrected
                </Badge>
              ) : isAccepted ? (
                <Badge variant="success" size="sm" className="gap-1">
                  <Check className="h-3 w-3" /> Accepted Match
                </Badge>
              ) : (
                <Badge variant="warning" size="sm" className="gap-1">
                  <HelpCircle className="h-3 w-3" /> Awaiting Review
                </Badge>
              )}

              {track.hasConflict && (
                <Badge variant="destructive" size="sm" className="gap-1">
                  <AlertTriangle className="h-3 w-3" /> Duplicate Conflict
                </Badge>
              )}
            </div>
          </div>
          <p className="text-xs text-muted-foreground mt-0.5">{track.sourceArtist}</p>
        </CardHeader>

        <CardContent className="p-4 pt-2 space-y-3">
          {/* Conflict Reason Alert */}
          {track.hasConflict && track.conflictReason && (
            <div className="rounded bg-amber-500/10 border border-amber-500/30 p-2 text-[11px] text-amber-400 flex items-start gap-1.5">
              <AlertTriangle className="h-3.5 w-3.5 shrink-0 mt-0.5" />
              <span>{track.conflictReason}</span>
            </div>
          )}

          {/* Destination Match Candidate Box */}
          {!isExcluded && activeCandidate ? (
            <div className="rounded-md border border-border/60 bg-secondary/30 p-2.5 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
              <div className="flex items-start gap-2.5 min-w-0">
                <PlaySquare className="h-4 w-4 text-youtube shrink-0 mt-0.5" />
                <div className="min-w-0">
                  <div className="text-xs font-medium text-foreground truncate">
                    {activeCandidate.title}
                  </div>
                  <div className="text-[11px] text-muted-foreground flex items-center gap-2 mt-0.5">
                    <span className="truncate">{activeCandidate.channelOrArtist || 'Unknown Channel'}</span>
                    {activeCandidate.durationMs && (
                      <span className="inline-flex items-center gap-1 font-mono text-[10px] shrink-0">
                        <Clock className="h-2.5 w-2.5" />
                        {formatDuration(activeCandidate.durationMs)}
                      </span>
                    )}
                  </div>
                </div>
              </div>

              {track.originalMatchScore !== null && track.originalMatchScore !== undefined && (
                <div className="flex items-center gap-1.5 shrink-0 text-[11px] font-mono text-muted-foreground bg-background/60 px-2 py-0.5 rounded border border-border/40">
                  <Sparkles className="h-3 w-3 text-primary" />
                  Score: {Math.round(track.originalMatchScore * 100)}%
                </div>
              )}
            </div>
          ) : !isExcluded ? (
            <div className="rounded-md border border-dashed border-border/60 bg-secondary/10 p-3 text-center text-xs text-muted-foreground">
              No matching candidate found on YouTube. Use search to find a replacement.
            </div>
          ) : null}

          {/* Action Buttons */}
          <div className="flex flex-wrap items-center justify-end gap-2 pt-1">
            {!isExcluded && activeCandidate && !isAccepted && (
              <Button
                variant="outline"
                size="sm"
                disabled={isActionPending}
                onClick={handleAcceptClick}
                className="h-8 text-xs gap-1.5 border-emerald-500/40 hover:bg-emerald-500/10 hover:text-emerald-400"
              >
                {isActionPending ? <Loader2 className="h-3 w-3 animate-spin" /> : <Check className="h-3 w-3" />}
                Accept Match
              </Button>
            )}

            <Button
              variant="outline"
              size="sm"
              disabled={isActionPending}
              onClick={() => setSearchOpen(true)}
              className="h-8 text-xs gap-1.5"
            >
              <Search className="h-3 w-3" />
              {activeCandidate ? 'Find Alternative' : 'Search Candidate'}
            </Button>

            {!isExcluded ? (
              <Button
                variant="ghost"
                size="sm"
                disabled={isActionPending}
                onClick={handleExcludeClick}
                className="h-8 text-xs gap-1 text-muted-foreground hover:text-destructive"
              >
                <Ban className="h-3 w-3" /> Exclude
              </Button>
            ) : (
              <Button
                variant="ghost"
                size="sm"
                disabled={isActionPending}
                onClick={handleAcceptClick}
                className="h-8 text-xs gap-1 text-muted-foreground hover:text-foreground"
              >
                Restore Track
              </Button>
            )}
          </div>
        </CardContent>
      </Card>

      <CandidateSearchDialog
        open={searchOpen}
        onOpenChange={setSearchOpen}
        track={track}
        sessionId={sessionId}
        onSelectCandidate={handleSelectCandidate}
      />
    </>
  );
}
