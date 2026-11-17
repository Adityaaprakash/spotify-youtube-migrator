import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { authApi } from '../features/auth/api.ts';
import { loginSchema, registerSchema } from '../features/auth/schemas.ts';
import { ApiException } from '../lib/api/types.ts';

describe('Authentication Operations & Validation Tests', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    (globalThis as unknown as { document?: { cookie: string } }).document = {
      cookie: 'XSRF-TOKEN=test-csrf-token',
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  describe('Session Initialization & Inspection (GET /api/auth/me)', () => {
    it('retrieves user session when authenticated', async () => {
      const mockUser = {
        id: '11111111-2222-3333-4444-555555555555',
        email: 'alice@example.com',
        displayName: 'Alice Musician',
        status: 'ACTIVE',
      };

      globalThis.fetch = async () => {
        return new Response(JSON.stringify(mockUser), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const user = await authApi.getCurrentUser();
      assert.strictEqual(user.id, mockUser.id);
      assert.strictEqual(user.email, mockUser.email);
      assert.strictEqual(user.displayName, mockUser.displayName);
      assert.strictEqual(user.status, 'ACTIVE');
    });

    it('throws ApiException with 401 when anonymous / unauthenticated', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            code: 'UNAUTHORIZED',
            message: 'Full authentication is required to access this resource',
            timestamp: '2026-11-17T10:00:00Z',
            path: '/api/auth/me',
          }),
          { status: 401, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await authApi.getCurrentUser();
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 401);
          return true;
        }
      );
    });

    it('distinguishes server failure from unauthenticated session', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            code: 'INTERNAL_SERVER_ERROR',
            message: 'Database connection failed',
            timestamp: '2026-11-17T10:00:00Z',
            path: '/api/auth/me',
          }),
          { status: 500, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await authApi.getCurrentUser();
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 500);
          assert.strictEqual(err.code, 'INTERNAL_SERVER_ERROR');
          return true;
        }
      );
    });
  });

  describe('Login Workflow (POST /api/auth/login)', () => {
    it('successfully authenticates with valid credentials', async () => {
      const mockUser = {
        id: '22222222-3333-4444-5555-666666666666',
        email: 'bob@example.com',
        displayName: 'Bob Producer',
        status: 'ACTIVE',
      };

      let sentPayload = '';
      globalThis.fetch = async (_url, init) => {
        sentPayload = String(init?.body);
        return new Response(JSON.stringify(mockUser), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await authApi.login({
        email: 'bob@example.com',
        password: 'securePassword123',
      });

      assert.strictEqual(result.email, 'bob@example.com');
      assert.ok(sentPayload.includes('bob@example.com'));
      assert.ok(sentPayload.includes('securePassword123'));
    });

    it('rejects invalid credentials with 404/NOT_FOUND', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            code: 'NOT_FOUND',
            message: 'Invalid email or password.',
            timestamp: '2026-11-17T10:00:00Z',
            path: '/api/auth/login',
          }),
          { status: 404, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await authApi.login({ email: 'bob@example.com', password: 'wrongPassword' });
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 404);
          assert.strictEqual(err.message, 'Invalid email or password.');
          return true;
        }
      );
    });
  });

  describe('Registration Workflow (POST /api/auth/register)', () => {
    it('successfully registers and establishes user session', async () => {
      const mockNewUser = {
        id: '33333333-4444-5555-6666-777777777777',
        email: 'carol@example.com',
        displayName: 'Carol Artist',
        status: 'ACTIVE',
      };

      globalThis.fetch = async () => {
        return new Response(JSON.stringify(mockNewUser), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        });
      };

      const result = await authApi.register({
        email: 'carol@example.com',
        password: 'password123',
        displayName: 'Carol Artist',
      });

      assert.strictEqual(result.id, mockNewUser.id);
      assert.strictEqual(result.email, 'carol@example.com');
      assert.strictEqual(result.displayName, 'Carol Artist');
    });

    it('throws 409 Conflict when account email already exists', async () => {
      globalThis.fetch = async () => {
        return new Response(
          JSON.stringify({
            code: 'CONFLICT',
            message: 'User with this email already exists.',
            timestamp: '2026-11-17T10:00:00Z',
            path: '/api/auth/register',
          }),
          { status: 409, headers: { 'Content-Type': 'application/json' } }
        );
      };

      await assert.rejects(
        async () => {
          await authApi.register({
            email: 'duplicate@example.com',
            password: 'password123',
            displayName: 'Duplicate User',
          });
        },
        (err: unknown) => {
          assert.ok(err instanceof ApiException);
          assert.strictEqual(err.status, 409);
          assert.strictEqual(err.code, 'CONFLICT');
          assert.strictEqual(err.message, 'User with this email already exists.');
          return true;
        }
      );
    });
  });

  describe('Logout Workflow (POST /api/auth/logout)', () => {
    it('invalidates session returning 204 No Content', async () => {
      let logoutCalled = false;
      globalThis.fetch = async (_url, init) => {
        assert.strictEqual(init?.method, 'POST');
        logoutCalled = true;
        return new Response(null, { status: 204, statusText: 'No Content' });
      };

      await authApi.logout();
      assert.strictEqual(logoutCalled, true);
    });
  });

  describe('Zod Validation Schemas', () => {
    it('validates login form input', () => {
      const valid = loginSchema.safeParse({ email: 'user@example.com', password: 'secretPassword' });
      assert.strictEqual(valid.success, true);

      const invalidEmail = loginSchema.safeParse({ email: 'not-an-email', password: 'secretPassword' });
      assert.strictEqual(invalidEmail.success, false);

      const emptyPassword = loginSchema.safeParse({ email: 'user@example.com', password: '' });
      assert.strictEqual(emptyPassword.success, false);
    });

    it('validates registration form input and password matching', () => {
      const valid = registerSchema.safeParse({
        displayName: 'Dave',
        email: 'dave@example.com',
        password: 'password123',
        confirmPassword: 'password123',
      });
      assert.strictEqual(valid.success, true);

      const shortPassword = registerSchema.safeParse({
        displayName: 'Dave',
        email: 'dave@example.com',
        password: '12345',
        confirmPassword: '12345',
      });
      assert.strictEqual(shortPassword.success, false);

      const passwordMismatch = registerSchema.safeParse({
        displayName: 'Dave',
        email: 'dave@example.com',
        password: 'password123',
        confirmPassword: 'differentPassword',
      });
      assert.strictEqual(passwordMismatch.success, false);
    });
  });
});
