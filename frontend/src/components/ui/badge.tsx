import React from 'react';
import { cn } from '@/lib/utils/cn';

export interface BadgeProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: 'default' | 'secondary' | 'outline' | 'success' | 'warning' | 'destructive' | 'spotify' | 'youtube';
  size?: 'default' | 'sm';
}

export function Badge({ className, variant = 'default', size = 'default', ...props }: BadgeProps) {
  const baseStyles = 'inline-flex items-center font-medium rounded-full transition-colors focus:outline-none';

  const variants = {
    default: 'bg-emerald-950/50 text-emerald-200 border border-emerald-500/40',
    secondary: 'bg-secondary text-secondary-foreground border border-border',
    outline: 'border border-border text-foreground',
    success: 'bg-emerald-950/50 text-emerald-200 border border-emerald-500/40',
    warning: 'bg-amber-950/50 text-amber-200 border border-amber-500/40',
    destructive: 'bg-rose-950/50 text-rose-200 border border-rose-500/40',
    spotify: 'bg-emerald-950/50 text-emerald-200 border border-spotify/40',
    youtube: 'bg-rose-950/50 text-rose-200 border border-youtube/40',
  };

  const sizes = {
    default: 'px-2.5 py-0.5 text-xs',
    sm: 'px-2 py-0.5 text-xs font-semibold',
  };

  return <div className={cn(baseStyles, variants[variant], sizes[size], className)} {...props} />;
}
