import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Music, ExternalLink, ArrowRight } from 'lucide-react';
import { SpotifyPlaylistSummary } from '../types';
import { sanitizeDescription } from '../utils';

export interface PlaylistCardProps {
  playlist: SpotifyPlaylistSummary;
}

export function PlaylistCard({ playlist }: PlaylistCardProps) {
  const navigate = useNavigate();
  const [imageError, setImageError] = useState(false);

  const cleanDescription = sanitizeDescription(playlist.description);

  const handleCardClick = () => {
    navigate(`/playlists/${playlist.id}`);
  };

  const handleExternalClick = (e: React.MouseEvent) => {
    e.stopPropagation();
  };

  return (
    <Card
      onClick={handleCardClick}
      className="group cursor-pointer flex flex-col justify-between overflow-hidden border-border/70 hover:border-spotify/50 hover:shadow-md transition-all duration-200"
    >
      <div>
        <div className="relative aspect-video w-full bg-secondary/60 flex items-center justify-center overflow-hidden border-b border-border/40">
          {playlist.imageUrl && !imageError ? (
            <img
              src={playlist.imageUrl}
              alt={playlist.name}
              onError={() => setImageError(true)}
              className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105"
              loading="lazy"
            />
          ) : (
            <div className="flex flex-col items-center justify-center text-muted-foreground p-4">
              <Music className="h-10 w-10 mb-1 opacity-60 text-spotify" />
              <span className="text-[11px] font-medium tracking-wider uppercase opacity-60">Spotify Playlist</span>
            </div>
          )}

          <div className="absolute top-2.5 right-2.5">
            <Badge variant="spotify" size="sm" className="backdrop-blur-md bg-background/80 shadow-sm">
              Spotify
            </Badge>
          </div>
        </div>

        <CardHeader className="p-4 pb-2">
          <div className="flex items-start justify-between gap-2">
            <CardTitle className="text-base line-clamp-1 font-semibold group-hover:text-spotify transition-colors">
              {playlist.name}
            </CardTitle>
            {playlist.spotifyUrl && (
              <a
                href={playlist.spotifyUrl}
                target="_blank"
                rel="noreferrer noopener"
                onClick={handleExternalClick}
                title="Open in Spotify"
                className="text-muted-foreground hover:text-foreground p-1 rounded hover:bg-secondary/80 transition-colors shrink-0"
              >
                <ExternalLink className="h-3.5 w-3.5" />
              </a>
            )}
          </div>
          {playlist.ownerDisplayName && (
            <span className="text-xs text-muted-foreground/80 font-medium">
              By {playlist.ownerDisplayName}
            </span>
          )}
        </CardHeader>

        {cleanDescription && (
          <CardContent className="px-4 py-1">
            <CardDescription className="text-xs line-clamp-2 text-muted-foreground">
              {cleanDescription}
            </CardDescription>
          </CardContent>
        )}
      </div>

      <CardFooter className="p-4 pt-3 flex items-center justify-between border-t border-border/40 mt-3 bg-card/30">
        <span className="text-xs font-medium text-muted-foreground">
          {playlist.totalTracks ?? 0} {playlist.totalTracks === 1 ? 'track' : 'tracks'}
        </span>
        <Button
          variant="ghost"
          size="sm"
          className="h-7 text-xs gap-1 text-primary group-hover:translate-x-0.5 transition-transform p-0 hover:bg-transparent"
        >
          Inspect <ArrowRight className="h-3 w-3" />
        </Button>
      </CardFooter>
    </Card>
  );
}
