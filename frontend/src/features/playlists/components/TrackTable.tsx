import { useState, useMemo } from 'react';
import { TrackItem } from '../types';
import { formatDuration } from '../utils';
import { Input } from '@/components/ui/input';
import { Search, Clock, Disc3, Music2, X } from 'lucide-react';

export interface TrackTableProps {
  tracks: TrackItem[];
}

export function TrackTable({ tracks }: TrackTableProps) {
  const [searchTerm, setSearchTerm] = useState('');

  const filteredTracks = useMemo(() => {
    if (!searchTerm.trim()) {
      return tracks;
    }
    const query = searchTerm.toLowerCase().trim();
    return tracks.filter(
      (track) =>
        track.name.toLowerCase().includes(query) ||
        (track.artist && track.artist.toLowerCase().includes(query)) ||
        (track.album && track.album.toLowerCase().includes(query))
    );
  }, [tracks, searchTerm]);

  return (
    <div className="space-y-4">
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
        <div className="relative w-full sm:w-72">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground pointer-events-none" />
          <Input
            type="text"
            placeholder="Search tracks, artists, albums..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="pl-9 pr-8 h-9 text-xs"
          />
          {searchTerm && (
            <button
              type="button"
              onClick={() => setSearchTerm('')}
              className="absolute right-2.5 top-2.5 text-muted-foreground hover:text-foreground p-0.5 rounded transition-colors"
              aria-label="Clear search"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          )}
        </div>

        <div className="text-xs text-muted-foreground">
          {searchTerm ? (
            <span>
              Showing <strong className="text-foreground">{filteredTracks.length}</strong> of{' '}
              {tracks.length} tracks
            </span>
          ) : (
            <span>
              Total: <strong className="text-foreground">{tracks.length}</strong> tracks
            </span>
          )}
        </div>
      </div>

      <div className="rounded-lg border border-border/80 overflow-hidden bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs border-collapse">
            <thead>
              <tr className="border-b border-border/70 bg-secondary/40 text-muted-foreground uppercase tracking-wider font-semibold">
                <th className="py-3 px-3 text-center w-12">#</th>
                <th className="py-3 px-4">Title & Artist</th>
                <th className="py-3 px-4 hidden md:table-cell">Album</th>
                <th className="py-3 px-4 text-right w-20">
                  <span className="inline-flex items-center gap-1">
                    <Clock className="h-3.5 w-3.5" />
                  </span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {filteredTracks.length === 0 ? (
                <tr>
                  <td colSpan={4} className="py-12 text-center text-muted-foreground">
                    <div className="flex flex-col items-center justify-center space-y-2">
                      <Music2 className="h-8 w-8 text-muted-foreground/60" />
                      <p className="text-sm font-medium">
                        {searchTerm ? 'No tracks match your search' : 'No tracks in this playlist'}
                      </p>
                      {searchTerm && (
                        <p className="text-xs text-muted-foreground/80">
                          Try adjusting your search terms or clear the filter.
                        </p>
                      )}
                    </div>
                  </td>
                </tr>
              ) : (
                filteredTracks.map((track, index) => (
                  <tr
                    key={track.id || track.externalId || `${index}-${track.name}`}
                    className="hover:bg-secondary/30 transition-colors group"
                  >
                    <td className="py-3 px-3 text-center text-muted-foreground font-mono text-[11px] w-12">
                      {index + 1}
                    </td>
                    <td className="py-3 px-4 max-w-xs sm:max-w-md">
                      <div className="font-medium text-foreground truncate group-hover:text-primary transition-colors">
                        {track.name}
                      </div>
                      <div className="text-[11px] text-muted-foreground truncate">
                        {track.artist || 'Unknown Artist'}
                      </div>
                    </td>
                    <td className="py-3 px-4 hidden md:table-cell text-muted-foreground truncate max-w-xs">
                      {track.album ? (
                        <span className="inline-flex items-center gap-1.5 truncate">
                          <Disc3 className="h-3 w-3 shrink-0 opacity-50" />
                          <span className="truncate">{track.album}</span>
                        </span>
                      ) : (
                        <span className="text-muted-foreground/50">-</span>
                      )}
                    </td>
                    <td className="py-3 px-4 text-right font-mono text-[11px] text-muted-foreground w-20">
                      {formatDuration(track.durationMs)}
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
