import { Link } from 'react-router-dom';
import { Music2, ArrowRight } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { useOAuthStatus } from '@/features/oauth/useOAuthStatus';

export function AppHeader() {
  const spotify = useOAuthStatus('spotify');
  const google = useOAuthStatus('google');

  return (
    <header className="sticky top-0 z-40 w-full border-b border-border/80 bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div className="flex h-14 items-center justify-between px-4 sm:px-6">
        <Link to="/dashboard" className="flex items-center gap-3 hover:opacity-90 transition-opacity">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/20 text-primary border border-primary/30">
            <Music2 className="h-4 w-4" />
          </div>
          <div className="flex items-center gap-2">
            <span className="font-bold text-sm tracking-tight text-foreground">Migrator</span>
            <div className="hidden sm:flex items-center gap-1.5 text-xs text-muted-foreground">
              <span className="text-emerald-400 font-medium">Spotify</span>
              <ArrowRight className="h-3 w-3 text-muted-foreground" />
              <span className="text-rose-400 font-medium">YouTube Music</span>
            </div>
          </div>
        </Link>

        <div className="flex items-center gap-2 sm:gap-3">
          <Link to="/settings" className="hover:opacity-85 transition-opacity">
            <Badge
              variant={spotify.isConnected ? 'spotify' : 'outline'}
              size="sm"
              className="gap-1.5"
            >
              <span
                className={`h-1.5 w-1.5 rounded-full ${
                  spotify.isConnected ? 'bg-white' : 'bg-muted-foreground'
                }`}
              />
              <span className="hidden xs:inline">Spotify</span>
              {spotify.isConnected ? 'Connected' : 'Offline'}
            </Badge>
          </Link>

          <Link to="/settings" className="hover:opacity-85 transition-opacity">
            <Badge
              variant={google.isConnected ? 'youtube' : 'outline'}
              size="sm"
              className="gap-1.5"
            >
              <span
                className={`h-1.5 w-1.5 rounded-full ${
                  google.isConnected ? 'bg-white' : 'bg-muted-foreground'
                }`}
              />
              <span className="hidden xs:inline">YouTube</span>
              {google.isConnected ? 'Connected' : 'Offline'}
            </Badge>
          </Link>
        </div>
      </div>
    </header>
  );
}
