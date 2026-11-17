import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Music2 } from 'lucide-react';

export function LoginPage() {
  return (
    <div className="min-h-screen flex items-center justify-center p-4 bg-background">
      <Card className="w-full max-w-md border-border/80 bg-card/60">
        <CardHeader className="text-center space-y-2">
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-xl bg-primary/20 text-primary border border-primary/30">
            <Music2 className="h-6 w-6" />
          </div>
          <CardTitle className="text-xl">Spotify to YouTube Music</CardTitle>
          <CardDescription>Authentication and OAuth session management (Phase 15C)</CardDescription>
        </CardHeader>
        <CardContent className="text-center text-sm text-muted-foreground py-6">
          <p>Login and OAuth connection workflows will be integrated in Phase 15C.</p>
        </CardContent>
      </Card>
    </div>
  );
}
