import { Badge } from '@/components/ui/badge';
import {
  CheckCircle2,
  AlertCircle,
  AlertTriangle,
  Clock,
  Loader2,
  RotateCcw,
  Sparkles,
} from 'lucide-react';
import { MigrationExecutionStatus } from '@/features/execution/types';

export interface MigrationStatusBadgeProps {
  status: MigrationExecutionStatus | string;
  size?: 'default' | 'sm';
  className?: string;
}

export function MigrationStatusBadge({
  status,
  size = 'sm',
  className,
}: MigrationStatusBadgeProps) {
  switch (status) {
    case 'COMPLETED':
      return (
        <Badge variant="success" size={size} className={className}>
          <CheckCircle2 className="h-3 w-3 mr-1" />
          Completed
        </Badge>
      );
    case 'RUNNING':
      return (
        <Badge variant="default" size={size} className={`bg-blue-600 hover:bg-blue-600 text-white ${className}`}>
          <Loader2 className="h-3 w-3 mr-1 animate-spin" />
          In Progress
        </Badge>
      );
    case 'QUEUED':
      return (
        <Badge variant="secondary" size={size} className={`bg-purple-900/40 text-purple-300 border-purple-700/50 ${className}`}>
          <Clock className="h-3 w-3 mr-1" />
          Queued
        </Badge>
      );
    case 'INITIALIZED':
      return (
        <Badge variant="secondary" size={size} className={className}>
          <Sparkles className="h-3 w-3 mr-1 text-primary" />
          Initialized
        </Badge>
      );
    case 'ALLOCATING_DESTINATION':
      return (
        <Badge variant="default" size={size} className={`bg-cyan-600 hover:bg-cyan-600 text-white ${className}`}>
          <Loader2 className="h-3 w-3 mr-1 animate-spin" />
          Allocating Playlist
        </Badge>
      );
    case 'PARTIALLY_COMPLETED':
      return (
        <Badge variant="warning" size={size} className={className}>
          <AlertTriangle className="h-3 w-3 mr-1" />
          Partially Completed
        </Badge>
      );
    case 'FAILED':
      return (
        <Badge variant="destructive" size={size} className={className}>
          <AlertCircle className="h-3 w-3 mr-1" />
          Failed
        </Badge>
      );
    case 'RECOVERY_REQUIRED':
      return (
        <Badge variant="warning" size={size} className={`border-amber-600 text-amber-400 ${className}`}>
          <AlertTriangle className="h-3 w-3 mr-1" />
          Recovery Required
        </Badge>
      );
    case 'ROLLED_BACK':
      return (
        <Badge variant="secondary" size={size} className={`text-muted-foreground ${className}`}>
          <RotateCcw className="h-3 w-3 mr-1" />
          Rolled Back
        </Badge>
      );
    case 'PARTIALLY_ROLLED_BACK':
      return (
        <Badge variant="secondary" size={size} className={`text-amber-300 ${className}`}>
          <RotateCcw className="h-3 w-3 mr-1" />
          Partially Rolled Back
        </Badge>
      );
    default:
      return (
        <Badge variant="outline" size={size} className={className}>
          {status}
        </Badge>
      );
  }
}
