import { useState } from 'react';
import { Link } from 'react-router-dom';
import { PageHeader } from '@/components/shared/PageHeader';
import { EmptyState } from '@/components/shared/EmptyState';
import { ErrorState } from '@/components/shared/ErrorState';
import { TableRowSkeleton } from '@/components/shared/LoadingSkeleton';
import { Button } from '@/components/ui/button';
import { History, ListMusic, RotateCcw } from 'lucide-react';
import { useMigrationHistory } from '../hooks/useMigrationHistory';
import { MigrationHistoryFilters } from './MigrationHistoryFilters';
import { MigrationHistoryTable } from './MigrationHistoryTable';

export function MigrationHistoryPage() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<string>('');

  const { data, isLoading, isError, error, refetch, isFetching } = useMigrationHistory({
    page,
    size: 20,
    status: status || undefined,
  });

  const handleStatusChange = (newStatus: string) => {
    setStatus(newStatus);
    setPage(0); // Reset to first page on filter change
  };

  const handlePageChange = (newPage: number) => {
    setPage(newPage);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <PageHeader
          title="Migration History"
          description="Browse past migrations, inspect execution details, track outcomes, and audit timelines."
        />

        <Link to="/playlists" className="shrink-0">
          <Button size="sm" className="gap-2 bg-primary hover:bg-primary/90 text-primary-foreground font-semibold text-xs shadow-sm">
            <ListMusic className="h-4 w-4" />
            New Migration
          </Button>
        </Link>
      </div>

      {/* Status Filter Bar */}
      <MigrationHistoryFilters
        selectedStatus={status}
        onSelectStatus={handleStatusChange}
        isLoading={isFetching}
      />

      {/* Main Content Area */}
      {isLoading ? (
        <div className="rounded-lg border border-border/60 overflow-hidden bg-card/60 p-4 space-y-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <TableRowSkeleton key={i} columns={5} />
          ))}
        </div>
      ) : isError ? (
        <ErrorState
          error={error}
          title="Failed to Load Migration History"
          onRetry={() => void refetch()}
        />
      ) : data && data.content.length > 0 ? (
        <MigrationHistoryTable
          pageData={data}
          onPageChange={handlePageChange}
          isLoading={isFetching}
        />
      ) : status ? (
        /* Empty filtered state */
        <EmptyState
          icon={History}
          title="No Migrations Match Filter"
          description={`No migrations were found with status "${status}". Try selecting a different filter.`}
          action={
            <Button
              variant="outline"
              size="sm"
              onClick={() => handleStatusChange('')}
              className="gap-1.5 text-xs"
            >
              <RotateCcw className="h-3.5 w-3.5" />
              Clear Filter
            </Button>
          }
        />
      ) : (
        /* Empty overall history state */
        <EmptyState
          icon={History}
          title="No Migrations Yet"
          description="You haven't initiated any playlist migrations yet. Choose a Spotify playlist to start your first migration."
          action={
            <Link to="/playlists">
              <Button size="sm" className="gap-1.5 bg-primary hover:bg-primary/90 text-primary-foreground text-xs font-semibold">
                <ListMusic className="h-3.5 w-3.5" />
                Browse Spotify Playlists
              </Button>
            </Link>
          }
        />
      )}
    </div>
  );
}
