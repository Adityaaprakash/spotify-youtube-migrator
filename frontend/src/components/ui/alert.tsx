import React, { forwardRef } from 'react';
import { cn } from '@/lib/utils/cn';
import { AlertCircle, CheckCircle2, Info, AlertTriangle } from 'lucide-react';

export interface AlertProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: 'default' | 'destructive' | 'success' | 'warning';
}

export const Alert = forwardRef<HTMLDivElement, AlertProps>(
  ({ className, variant = 'default', children, ...props }, ref) => {
    const variants = {
      default: 'bg-secondary/60 text-foreground border-border [&>svg]:text-primary',
      destructive: 'bg-rose-500/10 text-rose-300 border-rose-500/30 [&>svg]:text-rose-400',
      success: 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30 [&>svg]:text-emerald-400',
      warning: 'bg-amber-500/10 text-amber-300 border-amber-500/30 [&>svg]:text-amber-400',
    };

    const icons = {
      default: <Info className="h-4 w-4 shrink-0" />,
      destructive: <AlertCircle className="h-4 w-4 shrink-0" />,
      success: <CheckCircle2 className="h-4 w-4 shrink-0" />,
      warning: <AlertTriangle className="h-4 w-4 shrink-0" />,
    };

    return (
      <div
        ref={ref}
        role="alert"
        className={cn(
          'relative w-full rounded-lg border p-4 flex gap-3 items-start text-sm',
          variants[variant],
          className
        )}
        {...props}
      >
        {icons[variant]}
        <div className="flex-1 space-y-1">{children}</div>
      </div>
    );
  }
);
Alert.displayName = 'Alert';

export const AlertTitle = forwardRef<HTMLParagraphElement, React.HTMLAttributes<HTMLHeadingElement>>(
  ({ className, ...props }, ref) => (
    <h5 ref={ref} className={cn('font-semibold leading-none tracking-tight', className)} {...props} />
  )
);
AlertTitle.displayName = 'AlertTitle';

export const AlertDescription = forwardRef<HTMLParagraphElement, React.HTMLAttributes<HTMLParagraphElement>>(
  ({ className, ...props }, ref) => (
    <div ref={ref} className={cn('text-sm opacity-90', className)} {...props} />
  )
);
AlertDescription.displayName = 'AlertDescription';
