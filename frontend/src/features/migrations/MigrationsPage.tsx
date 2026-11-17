import { PageHeader } from '@/components/shared/PageHeader';
import { EmptyState } from '@/components/shared/EmptyState';
import { History } from 'lucide-react';

export function MigrationsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Migration History"
        description="View past migrations, track outcomes, and audit timelines."
      />

      <EmptyState
        icon={History}
        title="No migrations displayed"
        description="Migration history listing and status filtering will be integrated in Phase 15G."
      />
    </div>
  );
}
