import { PageHeader } from '@/components/shared/PageHeader';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Settings2 } from 'lucide-react';

export function SettingsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Settings"
        description="Manage your account preferences and connected music service authorizations."
      />

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle className="text-base flex items-center gap-2">
                <Settings2 className="h-4 w-4 text-primary" />
                Service Connections
              </CardTitle>
              <Badge variant="outline" size="sm">OAuth</Badge>
            </div>
            <CardDescription>Manage Spotify and Google/YouTube account authorizations.</CardDescription>
          </CardHeader>
          <CardContent className="text-sm text-muted-foreground">
            OAuth account connections and token lifecycles will be integrated in Phase 15C.
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
