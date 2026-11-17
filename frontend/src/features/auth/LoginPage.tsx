import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { Music2, ArrowRight } from 'lucide-react';
import { useAuth } from './useAuth';
import { loginSchema, LoginFormData } from './schemas';
import { ApiException } from '@/lib/api/types';
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Alert, AlertTitle, AlertDescription } from '@/components/ui/alert';

export function LoginPage() {
  const { login, isSessionExpired, dismissSessionExpired } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [serverError, setServerError] = useState<string | null>(null);

  const from = (location.state as { from?: Location })?.from?.pathname || '/dashboard';

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormData>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      email: '',
      password: '',
    },
  });

  const onSubmit = async (data: LoginFormData) => {
    setServerError(null);
    try {
      await login(data);
      dismissSessionExpired();
      navigate(from, { replace: true });
    } catch (err) {
      if (err instanceof ApiException) {
        if (err.status === 404 || err.status === 401) {
          setServerError('Invalid email or password. Please verify your credentials.');
        } else if (err.status === 403) {
          setServerError('Your account has been disabled. Please contact support.');
        } else {
          setServerError(err.details.message || 'An unexpected error occurred during login.');
        }
      } else if (err instanceof Error) {
        setServerError(err.message || 'Unable to connect to the authentication service.');
      } else {
        setServerError('An unexpected error occurred. Please try again.');
      }
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-4 bg-background">
      <Card className="w-full max-w-md border-border/80 bg-card/80 backdrop-blur">
        <CardHeader className="text-center space-y-2">
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-xl bg-primary/20 text-primary border border-primary/30">
            <Music2 className="h-6 w-6" />
          </div>
          <CardTitle className="text-2xl font-bold tracking-tight">Sign In</CardTitle>
          <CardDescription>
            Enter your credentials to access your playlist migration dashboard
          </CardDescription>
        </CardHeader>

        <form onSubmit={handleSubmit(onSubmit)}>
          <CardContent className="space-y-4">
            {isSessionExpired && (
              <Alert variant="warning" className="mb-2">
                <AlertTitle>Session Expired</AlertTitle>
                <AlertDescription>
                  Your previous session has ended. Please sign in again to continue.
                </AlertDescription>
              </Alert>
            )}

            {serverError && (
              <Alert variant="destructive" className="mb-2">
                <AlertTitle>Sign In Failed</AlertTitle>
                <AlertDescription>{serverError}</AlertDescription>
              </Alert>
            )}

            <div className="space-y-1.5">
              <Label htmlFor="email" required>
                Email Address
              </Label>
              <Input
                id="email"
                type="email"
                placeholder="name@example.com"
                autoComplete="email"
                disabled={isSubmitting}
                aria-invalid={Boolean(errors.email)}
                {...register('email')}
              />
              {errors.email && (
                <p className="text-xs text-destructive mt-1">{errors.email.message}</p>
              )}
            </div>

            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <Label htmlFor="password" required>
                  Password
                </Label>
              </div>
              <Input
                id="password"
                type="password"
                placeholder="••••••••"
                autoComplete="current-password"
                disabled={isSubmitting}
                aria-invalid={Boolean(errors.password)}
                {...register('password')}
              />
              {errors.password && (
                <p className="text-xs text-destructive mt-1">{errors.password.message}</p>
              )}
            </div>
          </CardContent>

          <CardFooter className="flex flex-col space-y-4 pt-2">
            <Button
              type="submit"
              className="w-full"
              isLoading={isSubmitting}
              disabled={isSubmitting}
            >
              Sign In
              <ArrowRight className="ml-2 h-4 w-4" />
            </Button>

            <div className="text-center text-xs text-muted-foreground">
              Don&apos;t have an account?{' '}
              <Link
                to="/register"
                className="text-primary hover:underline font-medium transition-colors"
              >
                Create an account
              </Link>
            </div>
          </CardFooter>
        </form>
      </Card>
    </div>
  );
}
