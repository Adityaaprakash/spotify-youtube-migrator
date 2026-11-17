import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Music, PlaySquare, ArrowRight, ExternalLink, ChevronLeft, ChevronRight } from 'lucide-react';
import { MigrationHistoryItem, PagedResponse } from '../types';
import { MigrationStatusBadge } from './MigrationStatusBadge';

export interface MigrationHistoryTableProps {
  pageData: PagedResponse<MigrationHistoryItem>;
  onPageChange: (newPage: number) => void;
  isLoading?: boolean;
}

function formatDate(isoString: string): string {
  try {
    const date = new Date(isoString);
    return date.toLocaleDateString(undefined, {
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

export function MigrationHistoryTable({
  pageData,
  onPageChange,
  isLoading = false,
}: MigrationHistoryTableProps) {
  const { content, page, totalElements, totalPages, first, last } = pageData;

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-border/70 overflow-hidden bg-card/60 shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="bg-secondary/40 text-xs uppercase tracking-wider text-muted-foreground border-b border-border/60">
              <tr>
                <th className="py-3 px-4 font-semibold">Source Playlist</th>
                <th className="py-3 px-4 font-semibold">Destination</th>
                <th className="py-3 px-4 font-semibold">Status</th>
                <th className="py-3 px-4 font-semibold">Track Outcomes</th>
                <th className="py-3 px-4 font-semibold">Started / Created</th>
                <th className="py-3 px-4 font-semibold text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {content.map((item) => {
                const active = isActiveStatus(item.status);
                const totalTracks = item.totalApprovedTracks || item.totalSourceTracks;

                return (
                  <tr
                    key={item.migrationId}
                    className="hover:bg-accent/30 transition-colors"
                  >
                    {/* Source Playlist */}
                    <td className="py-3.5 px-4 font-medium text-foreground">
                      <div className="flex items-center gap-2.5">
                        <div className="h-8 w-8 rounded bg-spotify/15 flex items-center justify-center shrink-0 text-spotify">
                          <Music className="h-4 w-4" />
                        </div>
                        <div>
                          <div className="font-semibold text-foreground line-clamp-1">
                            {item.sourcePlaylistTitle || 'Untitled Playlist'}
                          </div>
                          <span className="text-[11px] text-muted-foreground/70 font-mono">
                            {item.sourcePlaylistId}
                          </span>
                        </div>
                      </div>
                    </td>

                    {/* Destination */}
                    <td className="py-3.5 px-4 text-xs">
                      {item.destinationPlaylistUrl ? (
                        <a
                          href={item.destinationPlaylistUrl}
                          target="_blank"
                          rel="noreferrer noopener"
                          className="inline-flex items-center gap-1 text-youtube hover:underline font-medium"
                        >
                          YouTube Music <ExternalLink className="h-3 w-3" />
                        </a>
                      ) : (
                        <span className="text-muted-foreground">
                          {item.destinationPlatform || 'YouTube Music'}
                        </span>
                      )}
                    </td>

                    {/* Status */}
                    <td className="py-3.5 px-4">
                      <MigrationStatusBadge status={item.status} />
                    </td>

                    {/* Track Outcomes */}
                    <td className="py-3.5 px-4 text-xs text-muted-foreground">
                      {item.status === 'COMPLETED' ? (
                        <span className="text-emerald-400 font-medium">
                          {item.successfulInsertions} of {totalTracks} added
                        </span>
                      ) : item.status === 'PARTIALLY_COMPLETED' ? (
                        <span>
                          <strong className="text-foreground">{item.successfulInsertions}</strong> added,{' '}
                          <strong className="text-destructive">{item.failedInsertions}</strong> failed
                        </span>
                      ) : item.status === 'FAILED' ? (
                        <span className="text-destructive">
                          {item.failedInsertions} failed
                          {item.failureSummary ? ` (${item.failureSummary})` : ''}
                        </span>
                      ) : active ? (
                        <span className="text-blue-400 font-medium">
                          {item.successfulInsertions} of {totalTracks} processed
                        </span>
                      ) : (
                        <span>
                          {item.successfulInsertions} of {totalTracks} tracks
                        </span>
                      )}
                    </td>

                    {/* Timestamps */}
                    <td className="py-3.5 px-4 text-xs text-muted-foreground whitespace-nowrap">
                      {formatDate(item.createdAt)}
                    </td>

                    {/* Actions */}
                    <td className="py-3.5 px-4 text-right">
                      {active ? (
                        <Link to={`/migrations/${item.migrationId}/progress`}>
                          <Button
                            variant="default"
                            size="sm"
                            className="h-8 gap-1.5 text-xs bg-primary hover:bg-primary/90 text-primary-foreground font-semibold"
                          >
                            <PlaySquare className="h-3.5 w-3.5" />
                            Live Progress
                          </Button>
                        </Link>
                      ) : (
                        <Link to={`/migrations/${item.migrationId}`}>
                          <Button
                            variant="outline"
                            size="sm"
                            className="h-8 gap-1 text-xs text-muted-foreground hover:text-foreground"
                          >
                            Details <ArrowRight className="h-3 w-3" />
                          </Button>
                        </Link>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      {/* Pagination Footer */}
      {totalPages > 1 && (
        <div className="flex flex-col sm:flex-row items-center justify-between gap-3 text-xs text-muted-foreground pt-1">
          <div>
            Showing Page <strong>{page + 1}</strong> of <strong>{totalPages}</strong> ({totalElements} total migrations)
          </div>

          <div className="flex items-center gap-1.5">
            <Button
              variant="outline"
              size="sm"
              disabled={first || isLoading}
              onClick={() => onPageChange(page - 1)}
              className="h-8 gap-1 px-3 text-xs"
            >
              <ChevronLeft className="h-3.5 w-3.5" /> Previous
            </Button>

            <span className="px-2 text-xs font-semibold text-foreground">
              {page + 1}
            </span>

            <Button
              variant="outline"
              size="sm"
              disabled={last || isLoading}
              onClick={() => onPageChange(page + 1)}
              className="h-8 gap-1 px-3 text-xs"
            >
              Next <ChevronRight className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
