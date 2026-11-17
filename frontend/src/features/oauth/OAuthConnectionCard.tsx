import { useState } from 'react';
import { Music2, PlaySquare, ExternalLink, Unlink, AlertCircle } from 'lucide-react';
import { useOAuthStatus } from './useOAuthStatus';
import { OAuthProvider } from './api';
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { ConfirmationDialog } from '@/components/shared/ConfirmationDialog';
import { Skeleton } from '@/components/ui/skeleton';

export interface OAuthConnectionCardProps {
  provider: OAuthProvider;
  title?: string;
  description?: string;
}

export function OAuthConnectionCard({
  provider,
  title,
  description,
}: OAuthConnectionCardProps) {
  const {
    status,
    isConnected,
    isLoading,
    isConnecting,
    isDisconnecting,
    error,
    connect,
    disconnect,
    clearError,
  } = useOAuthStatus(provider);

  const [isConfirmOpen, setIsConfirmOpen] = useState(false);

  const isSpotify = provider === 'spotify';
  const defaultTitle = isSpotify ? 'Spotify' : 'YouTube Music / Google';
  const defaultDesc = isSpotify
    ? 'Connect your Spotify account to discover and migrate your playlists.'
    : 'Connect your Google account to create and manage YouTube Music destination playlists.';

  const handleDisconnectConfirm = async () => {
    try {
      await disconnect();
      setIsConfirmOpen(false);
    } catch {
      // Error handled in hook
    }
  };

  return (
    <>
      <Card className="flex flex-col justify-between border-border/80 bg-card/60">
        <CardHeader>
          <div className="flex items-center justify-between gap-2">
            <div className="flex items-center gap-3">
              <div
                className={`flex h-10 w-10 items-center justify-center rounded-xl border ${
                  isSpotify
                    ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20'
                    : 'bg-rose-500/10 text-rose-400 border-rose-500/20'
                }`}
              >
                {isSpotify ? <Music2 className="h-5 w-5" /> : <PlaySquare className="h-5 w-5" />}
              </div>
              <div>
                <CardTitle className="text-base font-semibold">{title || defaultTitle}</CardTitle>
                <CardDescription className="text-xs mt-0.5">
                  {description || defaultDesc}
                </CardDescription>
              </div>
            </div>

            {isLoading ? (
              <Skeleton className="h-6 w-20 rounded-full" />
            ) : isConnected ? (
              <Badge variant={isSpotify ? 'spotify' : 'youtube'} size="sm">
                Connected
              </Badge>
            ) : (
              <Badge variant="secondary" size="sm">
                Disconnected
              </Badge>
            )}
          </div>
        </CardHeader>

        <CardContent className="space-y-3">
          {error && (
            <Alert variant="destructive" className="py-2 text-xs">
              <AlertCircle className="h-4 w-4" />
              <AlertDescription className="flex items-center justify-between">
                <span>{error}</span>
                <button
                  type="button"
                  onClick={clearError}
                  className="ml-2 font-bold underline hover:opacity-80"
                >
                  Dismiss
                </button>
              </AlertDescription>
            </Alert>
          )}

          {isLoading ? (
            <div className="space-y-2 py-2">
              <Skeleton className="h-4 w-3/4" />
              <Skeleton className="h-4 w-1/2" />
            </div>
          ) : isConnected && status ? (
            <div className="rounded-lg border border-border/50 bg-background/50 p-3 space-y-1 text-xs">
              <div className="flex justify-between text-muted-foreground">
                <span>Account ID</span>
                <span className="font-mono text-foreground font-medium">
                  {status.providerUserId || 'Connected'}
                </span>
              </div>
              {status.expiresAt && (
                <div className="flex justify-between text-muted-foreground">
                  <span>Token Status</span>
                  <span className="text-foreground">
                    {new Date(status.expiresAt) > new Date() ? 'Active' : 'Expired'}
                  </span>
                </div>
              )}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground py-1">
              No active session connected. Authorize with {isSpotify ? 'Spotify' : 'Google'} to
              enable playlist migrations.
            </p>
          )}
        </CardContent>

        <CardFooter className="pt-2">
          {isLoading ? (
            <Skeleton className="h-9 w-full rounded-md" />
          ) : isConnected ? (
            <Button
              variant="outline"
              size="sm"
              className="w-full text-muted-foreground hover:text-destructive hover:border-destructive/50"
              onClick={() => setIsConfirmOpen(true)}
              disabled={isDisconnecting}
              isLoading={isDisconnecting}
            >
              <Unlink className="mr-2 h-4 w-4" />
              Disconnect {isSpotify ? 'Spotify' : 'Google'}
            </Button>
          ) : (
            <Button
              variant={isSpotify ? 'spotify' : 'youtube'}
              size="sm"
              className="w-full"
              onClick={connect}
              disabled={isConnecting}
              isLoading={isConnecting}
            >
              <ExternalLink className="mr-2 h-4 w-4" />
              Connect {isSpotify ? 'Spotify' : 'Google'}
            </Button>
          )}
        </CardFooter>
      </Card>

      <ConfirmationDialog
        open={isConfirmOpen}
        onOpenChange={setIsConfirmOpen}
        title={`Disconnect ${isSpotify ? 'Spotify' : 'Google'} Account?`}
        description={`This will revoke local session tokens for ${
          isSpotify ? 'Spotify' : 'Google'
        }. You will need to re-authenticate before performing migrations involving this platform.`}
        confirmText="Disconnect"
        variant="destructive"
        isLoading={isDisconnecting}
        onConfirm={handleDisconnectConfirm}
      />
    </>
  );
}
