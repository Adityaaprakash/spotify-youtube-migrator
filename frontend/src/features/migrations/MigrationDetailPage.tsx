import { useParams, Link } from 'react-router-dom';
import { PageHeader } from '@/components/shared/PageHeader';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { ArrowLeft } from 'lucide-react';

export function MigrationDetailPage() {
  const { id } = useParams<{ id: string }>();

  return (
    <div className="space-y-6">
      <div className="mb-2">
        <Link to="/migrations">
          <Button variant="ghost" size="sm" className="gap-1.5 text-xs text-muted-foreground">
            <ArrowLeft className="h-3.5 w-3.5" /> Back to history
          </Button>
        </Link>
      </div>

      <PageHeader
        title="Migration Execution"
        description={`Authoritative details and track outcomes for execution ID: ${id || 'unknown'}`}
      />

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Track Outcomes & Provenance</CardTitle>
          <CardDescription>Execution detail, per-track records, audit timeline, and safe resume will be integrated in Phase 15G.</CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Execution ID: <code className="bg-secondary px-2 py-0.5 rounded text-xs text-foreground">{id}</code>
        </CardContent>
      </Card>
    </div>
  );
}
