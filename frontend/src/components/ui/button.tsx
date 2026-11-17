import React, { forwardRef } from 'react';
import { cn } from '@/lib/utils/cn';
import { Loader2 } from 'lucide-react';

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'default' | 'secondary' | 'outline' | 'ghost' | 'destructive' | 'spotify' | 'youtube';
  size?: 'default' | 'sm' | 'lg' | 'icon';
  isLoading?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = 'default', size = 'default', isLoading = false, disabled, children, ...props }, ref) => {
    const baseStyles = 'inline-flex items-center justify-center font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 select-none';

    const variants = {
      default: 'bg-primary text-primary-foreground hover:bg-primary/90 shadow-sm',
      secondary: 'bg-secondary text-secondary-foreground hover:bg-secondary/80 border border-border/50',
      outline: 'border border-border bg-transparent hover:bg-accent hover:text-accent-foreground text-foreground',
      ghost: 'hover:bg-accent hover:text-accent-foreground text-muted-foreground hover:text-foreground',
      destructive: 'bg-destructive text-destructive-foreground hover:bg-destructive/90 shadow-sm',
      spotify: 'bg-spotify text-black font-semibold hover:bg-spotify-dark shadow-sm',
      youtube: 'bg-youtube text-white font-semibold hover:bg-youtube-dark shadow-sm',
    };

    const sizes = {
      default: 'h-9 px-4 py-2 text-sm rounded-md gap-2',
      sm: 'h-8 px-3 text-xs rounded-md gap-1.5',
      lg: 'h-11 px-6 text-base rounded-md gap-2.5',
      icon: 'h-9 w-9 rounded-md',
    };

    return (
      <button
        ref={ref}
        disabled={disabled || isLoading}
        className={cn(baseStyles, variants[variant], sizes[size], className)}
        {...props}
      >
        {isLoading && <Loader2 className="h-4 w-4 animate-spin shrink-0" />}
        {children}
      </button>
    );
  }
);

Button.displayName = 'Button';
