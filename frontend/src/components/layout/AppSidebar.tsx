import { NavLink } from 'react-router-dom';
import { cn } from '@/lib/utils/cn';
import {
  LayoutDashboard,
  ListMusic,
  History,
  Settings,
} from 'lucide-react';

const navigationItems = [
  { name: 'Dashboard', href: '/dashboard', icon: LayoutDashboard },
  { name: 'Playlists', href: '/playlists', icon: ListMusic },
  { name: 'Migrations', href: '/migrations', icon: History },
  { name: 'Settings', href: '/settings', icon: Settings },
];

export function AppSidebar() {
  return (
    <aside className="w-60 border-r border-border/80 bg-card/20 shrink-0 hidden md:flex flex-col justify-between p-4">
      <div className="space-y-1">
        <div className="px-3 py-2 text-xs font-semibold text-muted-foreground uppercase tracking-wider">
          Navigation
        </div>
        <nav className="space-y-1">
          {navigationItems.map((item) => (
            <NavLink
              key={item.href}
              to={item.href}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
                  isActive
                    ? 'bg-primary/15 text-primary border border-primary/25 font-semibold'
                    : 'text-muted-foreground hover:bg-accent hover:text-foreground'
                )
              }
            >
              <item.icon className="h-4 w-4 shrink-0" />
              <span>{item.name}</span>
            </NavLink>
          ))}
        </nav>
      </div>

      <div className="p-3 rounded-lg border border-border/50 bg-secondary/30 text-xs text-muted-foreground">
        <p className="font-semibold text-foreground mb-1">Spotify to YouTube</p>
        <p className="text-[11px] leading-tight">Deterministic matching, human review, durable recovery.</p>
      </div>
    </aside>
  );
}
