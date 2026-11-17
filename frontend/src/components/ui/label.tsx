import React, { forwardRef } from 'react';
import { cn } from '@/lib/utils/cn';

export interface LabelProps extends React.LabelHTMLAttributes<HTMLLabelElement> {
  required?: boolean;
}

export const Label = forwardRef<HTMLLabelElement, LabelProps>(
  ({ className, required, children, ...props }, ref) => (
    <label
      ref={ref}
      className={cn(
        'text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70 text-foreground flex items-center gap-1 select-none',
        className
      )}
      {...props}
    >
      {children}
      {required && <span className="text-destructive font-bold">*</span>}
    </label>
  )
);
Label.displayName = 'Label';
