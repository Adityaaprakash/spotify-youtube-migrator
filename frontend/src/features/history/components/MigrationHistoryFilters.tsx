import { MigrationExecutionStatus } from '@/features/execution/types';
import { Button } from '@/components/ui/button';
import { Filter } from 'lucide-react';

export interface MigrationHistoryFiltersProps {
  selectedStatus: string;
  onSelectStatus: (status: string) => void;
  isLoading?: boolean;
}

const statusOptions: Array<{ label: string; value: string }> = [
  { label: 'All Statuses', value: '' },
  { label: 'Completed', value: 'COMPLETED' },
  { label: 'In Progress', value: 'RUNNING' },
  { label: 'Partially Completed', value: 'PARTIALLY_COMPLETED' },
  { label: 'Failed', value: 'FAILED' },
  { label: 'Recovery Required', value: 'RECOVERY_REQUIRED' },
  { label: 'Queued', value: 'QUEUED' },
];

export function MigrationHistoryFilters({
  selectedStatus,
  onSelectStatus,
  isLoading = false,
}: MigrationHistoryFiltersProps) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <div className="flex items-center gap-1.5 text-xs text-muted-foreground mr-1">
        <Filter className="h-3.5 w-3.5" />
        <span>Filter:</span>
      </div>

      {statusOptions.map((option) => {
        const isActive = selectedStatus === option.value;
        return (
          <Button
            key={option.value || 'all'}
            variant={isActive ? 'default' : 'outline'}
            size="sm"
            disabled={isLoading}
            onClick={() => onSelectStatus(option.value as MigrationExecutionStatus | '')}
            className={`h-7 px-2.5 text-xs font-medium rounded-full ${
              isActive
                ? 'bg-primary text-primary-foreground shadow-sm'
                : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {option.label}
          </Button>
        );
      })}
    </div>
  );
}
