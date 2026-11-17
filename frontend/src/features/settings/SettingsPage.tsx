import { PageHeader } from '@/components/shared/PageHeader';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { User, ShieldCheck } from 'lucide-react';
import { useAuth } from '@/features/auth/useAuth';
import { OAuthConnectionCard } from '@/features/oauth/OAuthConnectionCard';

export function SettingsPage() {
  const { user } = useAuth();

  return (
    <div className="space-y-6 max-w-5xl">
      <PageHeader
        title="Settings & Connections"
        description="Manage your account profile and external music service authorizations."
      />

      <div className="space-y-6">
        <div>
          <h2 className="text-base font-semibold text-foreground mb-3 flex items-center gap-2">
            <User className="h-4 w-4 text-primary" />
            Account Profile
          </h2>
          <Card className="border-border/80 bg-card/60">
            <CardHeader className="pb-3">
              <div className="flex items-center justify-between">
                <CardTitle className="text-sm font-semibold">User Credentials</CardTitle>
                <Badge variant="success" size="sm" className="gap-1">
                  <ShieldCheck className="h-3 w-3" />
                  {user?.status || 'ACTIVE'}
                </Badge>
              </div>
              <CardDescription className="text-xs">
                Your authenticated account details on the migrator platform.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs">
                <div className="rounded-lg border border-border/50 bg-background/50 p-3 space-y-1">
                  <p className="text-muted-foreground font-medium">Display Name</p>
                  <p className="text-foreground font-semibold text-sm">
                    {user?.displayName || 'N/A'}
                  </p>
                </div>
                <div className="rounded-lg border border-border/50 bg-background/50 p-3 space-y-1">
                  <p className="text-muted-foreground font-medium">Email Address</p>
                  <p className="text-foreground font-semibold text-sm">{user?.email || 'N/A'}</p>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>

        <div>
          <div className="mb-3">
            <h2 className="text-base font-semibold text-foreground flex items-center gap-2">
              Music Service Authorizations
            </h2>
            <p className="text-xs text-muted-foreground mt-0.5">
              Connect your music streaming accounts using secure server-side OAuth. Tokens remain
              encrypted on the backend.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <OAuthConnectionCard
              provider="spotify"
              title="Spotify"
              description="Authorizes read access to your Spotify library and playlists for migration."
            />
            <OAuthConnectionCard
              provider="google"
              title="YouTube Music"
              description="Authorizes destination playlist creation and track additions in your YouTube account."
            />
          </div>
        </div>
      </div>
    </div>
  );
}
