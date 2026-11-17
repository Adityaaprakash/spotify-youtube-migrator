import { useState, useMemo } from 'react';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import {
  Search,
  CheckCircle2,
  AlertCircle,
  Clock,
  Loader2,
  Ban,
  HelpCircle,
  PlaySquare,
  X,
  Music2,
} from 'lucide-react';
import { ExecutionTrackRecord } from '../types';

export interface ExecutionTrackTableProps {
  tracks: ExecutionTrackRecord[];
}

function getTrackStatusBadge(status: string) {
  switch (status) {
    case 'ADDED':
      return (
        <Badge variant="success" size="sm" className="gap-1 text-[10px]">
          <CheckCircle2 className="h-3 w-3" /> Added
        </Badge>
      );
    case 'ADDING':
      return (
        <Badge variant="default" size="sm" className="gap-1 text-[10px]">
          <Loader2 className="h-3 w-3 animate-spin" /> Adding...
        </Badge>
      );
    case 'PENDING':
      return (
        <Badge variant="outline" size="sm" className="gap-1 text-[10px] text-muted-foreground">
          <Clock className="h-2.5 w-2.5" /> Pending
        </Badge>
      );
    case 'FAILED':
      return (
        <Badge variant="destructive" size="sm" className="gap-1 text-[10px]">
          <AlertCircle className="h-3 w-3" /> Failed
        </Badge>
      );
    case 'SKIPPED':
      return (
        <Badge variant="secondary" size="sm" className="gap-1 text-[10px]">
          <Ban className="h-2.5 w-2.5" /> Skipped
        </Badge>
      );
    case 'UNCERTAIN':
      return (
        <Badge variant="warning" size="sm" className="gap-1 text-[10px]">
          <HelpCircle className="h-3 w-3" /> Uncertain
        </Badge>
      );
    default:
      return (
        <Badge variant="outline" size="sm" className="text-[10px]">
          {status}
        </Badge>
      );
  }
}

export function ExecutionTrackTable({ tracks }: ExecutionTrackTableProps) {
  const [searchTerm, setSearchTerm] = useState('');

  const filteredTracks = useMemo(() => {
    if (!searchTerm.trim()) return tracks;
    const q = searchTerm.toLowerCase().trim();
    return tracks.filter(
      (t) =>
        t.sourceTitle.toLowerCase().includes(q) ||
        t.sourceArtist.toLowerCase().includes(q) ||
        (t.destinationTitle && t.destinationTitle.toLowerCase().includes(q)) ||
        (t.status && t.status.toLowerCase().includes(q))
    );
  }, [tracks, searchTerm]);

  return (
    <div className="space-y-3">
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
        <div className="relative w-full sm:w-72">
          <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-muted-foreground pointer-events-none" aria-hidden="true" />
          <Input
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder="Filter executed tracks..."
            aria-label="Filter executed tracks"
            className="pl-8 pr-8 h-8 text-xs"
          />
          {searchTerm && (
            <button
              type="button"
              onClick={() => setSearchTerm('')}
              className="absolute right-2.5 top-2 text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring rounded"
              aria-label="Clear filter"
            >
              <X className="h-3.5 w-3.5" aria-hidden="true" />
            </button>
          )}
        </div>

        <div className="text-xs text-muted-foreground">
          Showing <strong className="text-foreground">{filteredTracks.length}</strong> of{' '}
          {tracks.length} tracks
        </div>
      </div>

      <div className="rounded-lg border border-border/80 overflow-hidden bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs border-collapse">
            <thead>
              <tr className="border-b border-border/70 bg-secondary/40 text-muted-foreground uppercase tracking-wider font-semibold">
                <th className="py-2.5 px-3 text-center w-12">#</th>
                <th className="py-2.5 px-4">Source Spotify Track</th>
                <th className="py-2.5 px-4">Destination YouTube Video</th>
                <th className="py-2.5 px-4 text-center w-32">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {filteredTracks.length === 0 ? (
                <tr>
                  <td colSpan={4} className="py-12 text-center text-muted-foreground">
                    <div className="flex flex-col items-center justify-center space-y-2">
                      <Music2 className="h-6 w-6 text-muted-foreground/60" />
                      <p className="text-xs font-medium">
                        {searchTerm ? 'No tracks match filter' : 'No track execution records available.'}
                      </p>
                    </div>
                  </td>
                </tr>
              ) : (
                filteredTracks.map((record) => (
                  <tr
                    key={record.id || `${record.targetPosition}-${record.sourceTitle}`}
                    className="hover:bg-secondary/20 transition-colors"
                  >
                    <td className="py-2.5 px-3 text-center font-mono text-[11px] text-muted-foreground">
                      {record.targetPosition + 1}
                    </td>

                    <td className="py-2.5 px-4 max-w-xs sm:max-w-sm">
                      <div className="font-semibold text-foreground truncate">{record.sourceTitle}</div>
                      <div className="text-[11px] text-muted-foreground truncate">
                        {record.sourceArtist}
                      </div>
                    </td>

                    <td className="py-2.5 px-4 max-w-xs sm:max-w-sm">
                      {record.destinationTitle ? (
                        <div className="flex items-start gap-2">
                          <PlaySquare className="h-3.5 w-3.5 text-youtube shrink-0 mt-0.5" />
                          <div className="min-w-0">
                            <div className="font-medium text-foreground truncate">
                              {record.destinationTitle}
                            </div>
                            <div className="text-[11px] text-muted-foreground truncate">
                              {record.destinationChannel || 'YouTube Music'}
                            </div>
                          </div>
                        </div>
                      ) : (
                        <span className="text-muted-foreground italic text-[11px]">
                          Pending allocation
                        </span>
                      )}

                      {record.errorMessage && (
                        <div className="mt-1 text-[11px] text-destructive bg-destructive/10 rounded px-1.5 py-0.5">
                          {record.errorMessage}
                        </div>
                      )}
                    </td>

                    <td className="py-2.5 px-4 text-center shrink-0">
                      {getTrackStatusBadge(record.status)}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
