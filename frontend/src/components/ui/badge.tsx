import React from 'react';
import { cn } from '@/lib/utils/cn';

export interface BadgeProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: 'default' | 'secondary' | 'outline' | 'success' | 'warning' | 'destructive' | 'spotify' | 'youtube';
  size?: 'default' | 'sm';
}

export function Badge({ className, variant = 'default', size = 'default', ...props }: BadgeProps) {
  const baseStyles = 'inline-flex items-center font-medium rounded-full transition-colors focus:outline-none';

  const variants = {
    default: 'bg-primary/20 text-primary border border-primary/30',
    secondary: 'bg-secondary text-secondary-foreground border border-border',
    outline: 'border border-border text-foreground',
    success: 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30',
    warning: 'bg-amber-500/15 text-amber-400 border border-amber-500/30',
    destructive: 'bg-rose-500/15 text-rose-400 border border-rose-500/30',
    spotify: 'bg-spotify/20 text-emerald-400 border border-spotify/30',
    youtube: 'bg-youtube/20 text-rose-400 border border-youtube/30',
  };

  const sizes = {
    default: 'px-2.5 py-0.5 text-xs',
    sm: 'px-2 py-0.2 text-[10px]',
  };

  return <div className={cn(baseStyles, variants[variant], sizes[size], className)} {...props} />;
}
