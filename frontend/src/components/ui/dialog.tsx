import React, { useEffect, useRef, useId } from 'react';
import { cn } from '@/lib/utils/cn';
import { X } from 'lucide-react';

export interface DialogProps {
  open: boolean;
  onOpenChange?: (open: boolean) => void;
  children: React.ReactNode;
}

const DialogContext = React.createContext<{
  titleId: string;
  descriptionId: string;
}>({
  titleId: '',
  descriptionId: '',
});

export function Dialog({ open, onOpenChange, children }: DialogProps) {
  const previousActiveElementRef = useRef<HTMLElement | null>(null);
  const titleId = useId();
  const descriptionId = useId();

  useEffect(() => {
    if (open) {
      // Save previously focused element to restore on close
      previousActiveElementRef.current = document.activeElement as HTMLElement | null;
      document.body.style.overflow = 'hidden';

      const handleKeyDown = (e: KeyboardEvent) => {
        if (e.key === 'Escape' && onOpenChange) {
          e.preventDefault();
          onOpenChange(false);
        }
      };

      window.addEventListener('keydown', handleKeyDown);

      return () => {
        document.body.style.overflow = 'unset';
        window.removeEventListener('keydown', handleKeyDown);
        // Restore focus to previous element on close
        if (previousActiveElementRef.current && typeof previousActiveElementRef.current.focus === 'function') {
          setTimeout(() => {
            previousActiveElementRef.current?.focus();
          }, 0);
        }
      };
    }
  }, [open, onOpenChange]);

  if (!open) return null;

  return (
    <DialogContext.Provider value={{ titleId, descriptionId }}>
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        {/* Backdrop */}
        <div
          aria-hidden="true"
          className="fixed inset-0 bg-black/70 backdrop-blur-sm transition-opacity"
          onClick={() => onOpenChange?.(false)}
        />
        <div className="relative z-50 w-full max-w-lg">{children}</div>
      </div>
    </DialogContext.Provider>
  );
}

export function DialogContent({
  className,
  children,
  onClose,
  'aria-label': ariaLabel,
}: {
  className?: string;
  children: React.ReactNode;
  onClose?: () => void;
  'aria-label'?: string;
}) {
  const { titleId, descriptionId } = React.useContext(DialogContext);
  const contentRef = useRef<HTMLDivElement>(null);

  // Focus trapping and initial focus
  useEffect(() => {
    const dialogElement = contentRef.current;
    if (!dialogElement) return;

    // Find all focusable elements inside dialog
    const getFocusableElements = () => {
      const selector = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
      return Array.from(dialogElement.querySelectorAll<HTMLElement>(selector)).filter(
        (el) => el.offsetParent !== null
      );
    };

    // Auto-focus the first focusable element or the dialog itself
    const focusable = getFocusableElements();
    if (focusable.length > 0) {
      focusable[0].focus();
    } else {
      dialogElement.focus();
    }

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Tab') return;

      const focusableElements = getFocusableElements();
      if (focusableElements.length === 0) {
        e.preventDefault();
        return;
      }

      const firstElement = focusableElements[0];
      const lastElement = focusableElements[focusableElements.length - 1];

      if (e.shiftKey) {
        if (document.activeElement === firstElement || !dialogElement.contains(document.activeElement)) {
          e.preventDefault();
          lastElement.focus();
        }
      } else {
        if (document.activeElement === lastElement || !dialogElement.contains(document.activeElement)) {
          e.preventDefault();
          firstElement.focus();
        }
      }
    };

    dialogElement.addEventListener('keydown', handleKeyDown);
    return () => {
      dialogElement.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  return (
    <div
      ref={contentRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby={ariaLabel ? undefined : titleId}
      aria-describedby={descriptionId}
      aria-label={ariaLabel}
      tabIndex={-1}
      className={cn(
        'relative w-full rounded-lg border border-border bg-card p-6 text-card-foreground shadow-2xl animate-in fade-in-0 zoom-in-95 focus:outline-none',
        className
      )}
    >
      {onClose && (
        <button
          type="button"
          onClick={onClose}
          aria-label="Close dialog"
          className="absolute right-4 top-4 rounded-sm p-1 text-muted-foreground hover:text-foreground hover:bg-accent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <X className="h-4 w-4" />
          <span className="sr-only">Close dialog</span>
        </button>
      )}
      {children}
    </div>
  );
}

export function DialogHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col space-y-1.5 text-left mb-4', className)} {...props} />;
}

export function DialogTitle({ className, id: customId, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  const { titleId } = React.useContext(DialogContext);
  return (
    <h2
      id={customId || titleId}
      className={cn('text-lg font-semibold leading-none tracking-tight text-foreground', className)}
      {...props}
    />
  );
}

export function DialogDescription({ className, id: customId, ...props }: React.HTMLAttributes<HTMLParagraphElement>) {
  const { descriptionId } = React.useContext(DialogContext);
  return (
    <p
      id={customId || descriptionId}
      className={cn('text-sm text-muted-foreground', className)}
      {...props}
    />
  );
}

export function DialogFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('flex flex-col-reverse sm:flex-row sm:justify-end sm:space-x-2 pt-4 border-t border-border/40 mt-6', className)}
      {...props}
    />
  );
}
