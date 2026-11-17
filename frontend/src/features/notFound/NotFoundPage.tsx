import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { EmptyState } from '@/components/shared/EmptyState';
import { Compass } from 'lucide-react';

export function NotFoundPage() {
  return (
    <div className="py-12 flex items-center justify-center">
      <EmptyState
        icon={Compass}
        title="Page Not Found"
        description="The requested page could not be found. Check the URL or navigate back to the dashboard."
        action={
          <Link to="/dashboard">
            <Button variant="default" size="sm">
              Return to Dashboard
            </Button>
          </Link>
        }
      />
    </div>
  );
}
