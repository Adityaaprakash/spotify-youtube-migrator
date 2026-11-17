import { useState } from 'react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Search, Loader2, PlaySquare, Music, Check, Clock } from 'lucide-react';
import { useCandidateSearch } from '../hooks/useCandidateSearch';
import { DestinationCandidate, ReviewTrack } from '../types';
import { formatDuration } from '@/features/playlists/utils';

export interface CandidateSearchDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  track: ReviewTrack | null;
  sessionId: string;
  onSelectCandidate: (candidate: DestinationCandidate) => Promise<void>;
}

export function CandidateSearchDialog({
  open,
  onOpenChange,
  track,
  sessionId,
  onSelectCandidate,
}: CandidateSearchDialogProps) {
  const [query, setQuery] = useState(track ? `${track.sourceTitle} ${track.sourceArtist}` : '');
  const [isSelectingId, setIsSelectingId] = useState<string | null>(null);

  const searchMutation = useCandidateSearch(sessionId);

  const handleSearch = (e?: React.FormEvent) => {
    e?.preventDefault();
    if (!track) return;
    searchMutation.mutate({
      position: track.position,
      request: { query: query.trim() || null, limit: 10 },
    });
  };

  const handleSelect = async (candidate: DestinationCandidate) => {
    setIsSelectingId(candidate.id);
    try {
      await onSelectCandidate(candidate);
      onOpenChange(false);
    } finally {
      setIsSelectingId(null);
    }
  };

  const candidates = searchMutation.data?.candidates ?? [];

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl max-h-[85vh] flex flex-col p-6">
        <DialogHeader>
          <DialogTitle className="text-base flex items-center gap-2">
            <Search className="h-4 w-4 text-primary" />
            Search Alternative Candidates
          </DialogTitle>
          <DialogDescription className="text-xs">
            Finding YouTube matches for:{' '}
            <strong className="text-foreground">
              {track?.sourceTitle} — {track?.sourceArtist}
            </strong>
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSearch} className="flex gap-2 my-2">
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search by track title, artist, or YouTube keywords..."
            aria-label="Search alternative candidate query"
            className="h-9 text-xs flex-1"
          />
          <Button
            type="submit"
            size="sm"
            disabled={searchMutation.isPending}
            className="h-9 text-xs gap-1.5"
          >
            {searchMutation.isPending ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <Search className="h-3.5 w-3.5" />
            )}
            Search
          </Button>
        </form>

        <div className="flex-1 overflow-y-auto space-y-2.5 pr-1 mt-2 min-h-[240px]">
          {searchMutation.isPending ? (
            <div className="flex flex-col items-center justify-center py-12 text-muted-foreground space-y-2">
              <Loader2 className="h-6 w-6 animate-spin text-primary" />
              <p className="text-xs">Searching YouTube Music candidates...</p>
            </div>
          ) : searchMutation.isError ? (
            <div className="py-8 text-center text-xs text-destructive bg-destructive/10 rounded-md p-4">
              Failed to search candidates: {searchMutation.error.message}
            </div>
          ) : candidates.length === 0 ? (
            <div className="py-12 text-center text-muted-foreground flex flex-col items-center justify-center space-y-2">
              <Music className="h-8 w-8 opacity-40" />
              <p className="text-xs">
                {searchMutation.data ? 'No candidates found for this query.' : 'Type a query or search to see candidate results.'}
              </p>
            </div>
          ) : (
            candidates.map((candidate) => {
              const isCurrentMatch =
                track?.selectedCandidate?.id === candidate.id ||
                track?.originalCandidate?.id === candidate.id;

              return (
                <div
                  key={candidate.id}
                  className="rounded-lg border border-border/80 p-3 bg-secondary/20 hover:bg-secondary/40 transition-colors flex items-center justify-between gap-3 text-xs"
                >
                  <div className="flex items-start gap-3 min-w-0 flex-1">
                    <div className="h-9 w-9 rounded bg-secondary/80 flex items-center justify-center shrink-0">
                      <PlaySquare className="h-4 w-4 text-youtube opacity-80" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="font-semibold text-foreground truncate">
                        {candidate.title}
                      </div>
                      <div className="text-muted-foreground text-[11px] flex items-center gap-2 mt-0.5">
                        <span className="truncate">{candidate.channelOrArtist || 'Unknown Channel'}</span>
                        {candidate.durationMs && (
                          <span className="flex items-center gap-1 shrink-0 font-mono text-[10px]">
                            <Clock className="h-3 w-3" />
                            {formatDuration(candidate.durationMs)}
                          </span>
                        )}
                      </div>
                    </div>
                  </div>

                  <div className="shrink-0 flex items-center gap-2">
                    {isCurrentMatch && (
                      <Badge variant="outline" size="sm" className="text-[10px]">
                        Current
                      </Badge>
                    )}
                    <Button
                      size="sm"
                      variant={isCurrentMatch ? 'secondary' : 'default'}
                      disabled={isSelectingId !== null}
                      onClick={() => void handleSelect(candidate)}
                      className="h-7 text-xs gap-1"
                    >
                      {isSelectingId === candidate.id ? (
                        <Loader2 className="h-3 w-3 animate-spin" />
                      ) : (
                        <Check className="h-3 w-3" />
                      )}
                      Select
                    </Button>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
