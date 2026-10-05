# Security Architecture

## Principles
1. NEVER commit secrets.
2. NEVER leak provider tokens to the frontend.
3. OAuth state and tokens are handled completely server-side.
4. Uses environment variables for configuration.
