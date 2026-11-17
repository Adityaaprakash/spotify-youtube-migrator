import { cn } from '@/lib/utils/cn';
import { AlertCircle, RotateCcw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ApiException } from '@/lib/api/types';

export interface ErrorStateProps {
  error: Error | ApiException | string | null;
  onRetry?: () => void;
  title?: string;
  className?: string;
}

export function ErrorState({ error, onRetry, title = 'Failed to load data', className }: ErrorStateProps) {
  let errorMessage = 'An unexpected error occurred';
  let errorCode: string | undefined;

  if (typeof error === 'string') {
    errorMessage = error;
  } else if (error instanceof ApiException) {
    errorMessage = error.details.message || errorMessage;
    errorCode = error.details.code;
  } else if (error instanceof Error) {
    errorMessage = error.message;
  }

  return (
    <div
      className={cn(
        'rounded-lg border border-destructive/40 bg-destructive/10 p-6 text-foreground flex flex-col md:flex-row items-start md:items-center justify-between gap-4',
        className
      )}
    >
      <div className="flex items-start gap-3">
        <AlertCircle className="h-5 w-5 text-destructive shrink-0 mt-0.5" />
        <div>
          <h4 className="text-sm font-semibold text-destructive">{title}</h4>
          <p className="text-sm text-muted-foreground mt-0.5">{errorMessage}</p>
          {errorCode && (
            <span className="inline-block mt-2 font-mono text-[11px] text-muted-foreground/80 bg-background/50 px-2 py-0.5 rounded border border-border/50">
              Code: {errorCode}
            </span>
          )}
        </div>
      </div>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry} className="shrink-0 gap-1.5 self-end md:self-center">
          <RotateCcw className="h-3.5 w-3.5" />
          Retry
        </Button>
      )}
    </div>
  );
}
