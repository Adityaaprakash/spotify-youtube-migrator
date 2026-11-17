import { useState } from 'react';
import { useMigrationAuditTimeline } from '../hooks/useMigrationAuditTimeline';
import { TableRowSkeleton } from '@/components/shared/LoadingSkeleton';
import { ErrorState } from '@/components/shared/ErrorState';
import { EmptyState } from '@/components/shared/EmptyState';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  History,
  ArrowRight,
  User,
  Cpu,
  Clock,
  RotateCcw,
  CheckCircle2,
  AlertCircle,
  Shield,
  ChevronLeft,
  ChevronRight,
} from 'lucide-react';
import { MigrationAuditEvent } from '../types';

export interface MigrationAuditTimelineProps {
  executionId: string;
}

function formatEventTime(isoString: string): string {
  try {
    const d = new Date(isoString);
    return d.toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    return isoString;
  }
}

function getEventIcon(eventType: string) {
  switch (eventType) {
    case 'STATE_TRANSITION':
      return <ArrowRight className="h-3.5 w-3.5 text-primary" />;
    case 'RETRY_SCHEDULED':
      return <RotateCcw className="h-3.5 w-3.5 text-amber-400" />;
    case 'LEASE_ACQUIRED':
    case 'LEASE_RENEWED':
      return <Shield className="h-3.5 w-3.5 text-cyan-400" />;
    case 'COMPLETED':
      return <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" />;
    case 'FAILED':
      return <AlertCircle className="h-3.5 w-3.5 text-destructive" />;
    default:
      return <Clock className="h-3.5 w-3.5 text-muted-foreground" />;
  }
}

function getActorBadge(actor?: string | null) {
  if (!actor) return null;
  const upper = actor.toUpperCase();
  const isUser = upper === 'USER';
  const isWorker = upper.includes('WORKER') || upper.includes('SCHEDULER') || upper.includes('NODE');

  return (
    <Badge
      variant="outline"
      size="sm"
      className="text-[10px] h-4 px-1.5 font-mono gap-1 text-muted-foreground border-border/60"
    >
      {isUser ? <User className="h-2.5 w-2.5" /> : isWorker ? <Cpu className="h-2.5 w-2.5" /> : null}
      {actor}
    </Badge>
  );
}

export function MigrationAuditTimeline({ executionId }: MigrationAuditTimelineProps) {
  const [page, setPage] = useState(0);

  const { data, isLoading, isError, error, refetch, isFetching } = useMigrationAuditTimeline(
    executionId,
    { page, size: 50 }
  );

  if (isLoading) {
    return (
      <div className="space-y-3 p-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <TableRowSkeleton key={i} columns={3} />
        ))}
      </div>
    );
  }

  if (isError) {
    return (
      <ErrorState
        error={error}
        title="Failed to Load Audit Timeline"
        onRetry={() => void refetch()}
      />
    );
  }

  const events: MigrationAuditEvent[] = data?.content ?? [];

  if (events.length === 0) {
    return (
      <EmptyState
        icon={History}
        title="No Audit Events Recorded"
        description="No state transitions or audit events were recorded for this migration execution."
      />
    );
  }

  return (
    <div className="space-y-4">
      <div className="relative pl-6 before:absolute before:left-2.5 before:top-2 before:bottom-2 before:w-0.5 before:bg-border/60 space-y-6">
        {events.map((event, index) => {
          return (
            <div key={event.id || index} className="relative group">
              {/* Timeline Icon Node */}
              <div className="absolute -left-6 top-1 h-5 w-5 rounded-full bg-card border border-border flex items-center justify-center shadow-sm">
                {getEventIcon(event.eventType)}
              </div>

              {/* Event Content Card */}
              <div className="rounded-lg border border-border/70 bg-card/60 p-3.5 space-y-2 hover:border-border transition-colors shadow-sm">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <Badge variant="secondary" size="sm" className="font-mono text-[11px] font-semibold">
                      {event.eventType}
                    </Badge>
                    {getActorBadge(event.actor)}
                  </div>

                  <span className="text-[11px] text-muted-foreground whitespace-nowrap">
                    {formatEventTime(event.createdAt)}
                  </span>
                </div>

                {/* State Transition Visual */}
                {(event.previousState || event.newState) && (
                  <div className="flex items-center gap-2 text-xs font-mono pt-1">
                    {event.previousState ? (
                      <span className="text-muted-foreground">{event.previousState}</span>
                    ) : (
                      <span className="text-muted-foreground/50">NONE</span>
                    )}
                    <ArrowRight className="h-3 w-3 text-primary shrink-0" />
                    <span className="text-foreground font-semibold">{event.newState || 'UNKNOWN'}</span>
                  </div>
                )}

                {/* Reason description */}
                {event.reason && (
                  <p className="text-xs text-muted-foreground leading-relaxed">
                    {event.reason}
                  </p>
                )}

                {/* Event Correlation / Details */}
                {(event.correlationId || event.details) && (
                  <div className="text-[11px] text-muted-foreground/80 font-mono bg-secondary/30 p-2 rounded border border-border/40 mt-1 break-all">
                    {event.correlationId && (
                      <div>
                        <span className="text-muted-foreground">correlationId:</span> {event.correlationId}
                      </div>
                    )}
                    {event.details && (
                      <div className="mt-0.5 text-foreground/80">
                        {event.details}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Pagination Footer */}
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground pt-2">
          <span>
            Timeline Page <strong>{data.page + 1}</strong> of <strong>{data.totalPages}</strong> ({data.totalElements} events)
          </span>

          <div className="flex items-center gap-1">
            <Button
              variant="outline"
              size="sm"
              disabled={data.first || isFetching}
              onClick={() => setPage(data.page - 1)}
              className="h-7 text-xs px-2.5 gap-1"
            >
              <ChevronLeft className="h-3.5 w-3.5" /> Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={data.last || isFetching}
              onClick={() => setPage(data.page + 1)}
              className="h-7 text-xs px-2.5 gap-1"
            >
              Next <ChevronRight className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
