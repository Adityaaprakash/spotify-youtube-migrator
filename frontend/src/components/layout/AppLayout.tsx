import { Outlet, NavLink } from 'react-router-dom';
import { AppHeader } from './AppHeader';
import { AppSidebar } from './AppSidebar';
import { cn } from '@/lib/utils/cn';
import { LayoutDashboard, ListMusic, History, Settings } from 'lucide-react';

const mobileNavItems = [
  { name: 'Dashboard', href: '/dashboard', icon: LayoutDashboard },
  { name: 'Playlists', href: '/playlists', icon: ListMusic },
  { name: 'Migrations', href: '/migrations', icon: History },
  { name: 'Settings', href: '/settings', icon: Settings },
];

export function AppLayout() {
  return (
    <div className="min-h-screen flex flex-col bg-background text-foreground">
      {/* Skip to Main Content Link for Keyboard Accessibility (WCAG 2.4.1) */}
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:px-4 focus:py-2 focus:bg-primary focus:text-primary-foreground focus:rounded-md focus:shadow-lg focus:outline-none focus:ring-2 focus:ring-ring font-medium text-xs"
      >
        Skip to main content
      </a>

      <AppHeader />
      <div className="flex-1 flex overflow-hidden">
        <AppSidebar />
        <main
          id="main-content"
          tabIndex={-1}
          className="flex-1 overflow-y-auto p-4 sm:p-6 md:p-8 max-w-7xl mx-auto w-full pb-20 md:pb-8 focus:outline-none"
        >
          <Outlet />
        </main>
      </div>

      {/* Mobile bottom navigation */}
      <nav
        aria-label="Mobile Navigation"
        className="md:hidden fixed bottom-0 left-0 right-0 z-40 border-t border-border/80 bg-background/95 backdrop-blur flex items-center justify-around h-14 px-2"
      >
        {mobileNavItems.map((item) => (
          <NavLink
            key={item.href}
            to={item.href}
            className={({ isActive }) =>
              cn(
                'flex flex-col items-center justify-center gap-1 text-[10px] font-medium transition-colors p-1 flex-1',
                isActive ? 'text-primary font-semibold' : 'text-muted-foreground hover:text-foreground'
              )
            }
          >
            <item.icon className="h-4 w-4" />
            <span>{item.name}</span>
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
