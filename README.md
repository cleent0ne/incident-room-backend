# Incident Room — Backend

Incident training as a game: a player is dropped into a simulated production incident, investigates
telemetry (logs/metrics/traces) to reveal what's happening, and picks a remediation. The backend is
the API and content-authoring system behind that — this repo is the API only. See `/docs` (Swagger
UI) once running for the live contract.

## What's implemented

**Accounts & sessions**
- Email/password registration with mandatory 6-digit email verification (hashed, TTL'd, attempt-capped)
- Login, with a distinct `EMAIL_NOT_VERIFIED` error so the frontend can route correctly
- OAuth2 login (Google + GitHub) with account-linking logic (same email → one account, not two)
- HTTP-only JWT cookies: short-lived access token + a separate, rotating, server-side-revocable
  opaque refresh token with reuse-detection
- `POST /api/v1/attempts/claim` — attaches attempts played anonymously to a user once they
  register/log in, tolerant of a partially-bad batch (unknown IDs, already-claimed, another user's)
- `GET /api/v1/users/me/attempts` — a user's attempt history, paginated, most recent first
- **Daily streaks** — consecutive UTC daily completions track on `AppUser.currentStreak` and are returned in user profile responses

**Content & Daily Incident**
- Scenarios authored as YAML, imported via an admin API (`POST /api/v1/admin/scenarios/import`),
  always persisted as a draft regardless of validity
- A hard validation gate on publish (`PATCH /api/v1/admin/scenarios/{id}/publish`) — dangling
  telemetry references, unsolvable scenarios (no correct remediation), and missing/oversized SEO
  metadata are all rejected before a scenario can go live
- Public, unauthenticated, cached (Caffeine) read API: `GET /api/v1/scenarios`,
  `GET /api/v1/scenarios/{slug}`, `GET /api/v1/scenarios/daily`, `GET /api/v1/sitemap-data`
- Admin content-management API — **backend-authoritative**, not client-side bookkeeping:
  `GET /api/v1/admin/scenarios` (paginated, drafts + published, optional `?published=` filter),
  `GET /api/v1/admin/scenarios/{id}` (full detail including the answer key, for review),
  `DELETE /api/v1/admin/scenarios/{id}` (refuses with 409 while the scenario is published —
  unpublish first)
- Admin scheduling API: `PATCH /api/v1/admin/scenarios/{id}/daily-date` to set or clear a scenario's UTC daily date
- **Daily incident always has an answer**: `GET /api/v1/scenarios/daily` prefers an explicitly
  scheduled `dailyDate` for today, but if nothing was scheduled it deterministically rotates
  through every published scenario keyed off the UTC epoch day — no admin action required for the
  daily feature to keep working, and every backend instance computes the same pick independently
  (no shared state needed)

**Gameplay**
- `POST /api/v1/attempts` — start an attempt, anonymous or authenticated (anonymous play is a
  first-class path, not a fallback)
- `POST /api/v1/attempts/{id}/actions` — take an investigative action, idempotent, reveals gated telemetry
- `PATCH /api/v1/attempts/{id}/reflection` — optional free-text hypothesis ("what do you think is happening?") saved before remediation
- `POST /api/v1/attempts/{id}/resolve` — submit a remediation, scored (base points + a capped
  time bonus against the scenario's target duration), idempotent on an already-completed attempt;
  response includes a `percentile` field and player's `reflectionText`

**Leaderboard**
- `GET /api/v1/scenarios/{slug}/leaderboard` — ranked leaderboard for a scenario, paginated, public and
  unauthenticated; sorted by score descending, then elapsed seconds ascending, then earliest achievedAt
- High-score upsert: only the single best result per user per scenario is kept — a re-attempt that beats
  the prior score (or same score with lower elapsed time) replaces it in the leaderboard
- Anonymous completions are retroactively posted to the leaderboard when `POST /attempts/claim` is called

**Cross-cutting**
- `/api/v1/admin/**` returns an identical 404 for both unauthenticated and wrong-role requests
- CSRF protection (double-submit cookie) on every mutating endpoint that isn't a pre-session entry point
- Rate limiting on auth + gameplay endpoints (in-memory Bucket4j) including `POST /attempts` (30 req/min) and `PATCH /attempts/*/reflection` (20 req/min)
- `resend-cooldown-minutes` enforced in `VerificationCodeService` — re-requesting a verification code
  within 1 minute returns HTTP 429 `RATE_LIMITED`
- Structured logging with correlation-ID propagation, Micrometer/Prometheus metrics, health checks
- BCrypt(12) password hashing, least-privilege-by-construction Mongo document design

## Requirements

- Java 21
- Maven 3.9+
- MongoDB (local Docker container for dev, MongoDB Atlas for prod — see `docker-compose.yml`)

## Running locally

```bash
docker compose up -d mongo
export JWT_SECRET="any-long-random-string-for-local-dev"
mvn spring-boot:run
```

The `local` Spring profile is active by default (see `application.yml`) and supplies a dev-only
fallback JWT secret if `JWT_SECRET` isn't set, so the app boots without any config for a first run —
but set a real one before doing anything that matters even locally, since it's the key that signs
every session.

API docs: `http://localhost:8080/docs`
Health: `http://localhost:8080/actuator/health`

Verification codes in local dev are written to the application log
(`[DEV EMAIL] Verification code for ...`) rather than actually emailed — see `ConsoleEmailService`.

## Seeding content

There's no self-service "become an admin" endpoint by design — role elevation is a deliberate
out-of-band action, not something the API grants itself. To seed scenario content locally:

1. Register a normal account through the API (or the frontend), then verify it using the code
   printed in the backend's log output.
2. Promote that account to `ADMIN` directly in Mongo:
   ```bash
   docker compose exec mongo mongosh incidentroom --eval \
     'db.users.updateOne({ email: "you@example.com" }, { $set: { role: "ADMIN" } })'
   ```
3. Run the seed script, which logs in as that admin and imports + publishes every scenario under
   `seed-data/`:
   ```bash
   ADMIN_EMAIL=you@example.com ADMIN_PASSWORD=your-password ./scripts/seed-scenarios.sh
   ```

See `seed-data/README.md` for the scenario YAML schema and the three example scenarios included
there (`cache-stampede-checkout`, `bad-deploy-error-spike`, `connection-pool-exhaustion`) — good
starting references for authoring new ones.

## Local dev: .env is loaded automatically

Copy `.env.example` to `.env` (gitignored) and fill in real values. The `springboot3-dotenv`
dependency loads it into Spring's environment automatically on `mvn spring-boot:run` — you do
**not** need to export the variables into your shell/OS environment yourself. This only applies
locally; production (Railway, etc.) sets real environment variables and has no `.env` file.

## Required environment variables (production)

| Variable | Purpose | Notes |
|---|---|---|
| `MONGODB_URI` | Atlas connection string | `mongodb+srv://...` |
| `JWT_SECRET` | Signs access tokens | No default in the `prod` profile — startup fails fast if unset, deliberately |
| `FRONTEND_BASE_URL` | CORS allow-list + OAuth redirect target | Exact origin, no wildcard |
| `COOKIE_DOMAIN` | Cookie `Domain` attribute | Only applied when `COOKIE_SECURE=true` |
| `COOKIE_SECURE` | Forces `Secure` flag | Set `true` in every real environment |
| `RESEND_API_KEY` | Resend transactional email API key | Required in `prod`; startup can boot without it but `sendVerificationCode` fails fast at call time |
| `EMAIL_FROM_ADDRESS` | Sender address for verification emails | Defaults to `noreply@incidentroom.com` if unset |
| `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` | Google OAuth2 | From Google Cloud Console — leave unset to disable Google login rather than hardcoding a fallback. **Authorized redirect URI to register in the Console: `<FRONTEND_BASE_URL>/login/oauth2/code/google`** — the frontend's origin, not this backend's Railway domain (see `OAuth2ClientRegistrationConfig`'s javadoc for why) |
| `GITHUB_OAUTH_CLIENT_ID` / `GITHUB_OAUTH_CLIENT_SECRET` | GitHub OAuth2 | From a GitHub OAuth App — same rule, no hardcoded fallback. **Authorization callback URL to register: `<FRONTEND_BASE_URL>/login/oauth2/code/github`** |

The `prod` profile activates `ResendEmailService` via `@Profile("prod")`. Set `RESEND_API_KEY` in your
deployment environment — the service will fail fast with a clear `IllegalStateException` if it reaches
a send call without a key configured, rather than silently swallowing the failure.

## Testing

```bash
mvn test
```

| Class | Covers |
|---|---|
| `AuthFlowIntegrationTest` | register → verify → login → refresh (rotation) → logout, plus admin-route 404-masking |
| `AttemptFlowIntegrationTest` | start → investigate → resolve scoring, idempotency, ownership boundaries, `claim` |
| `LeaderboardIntegrationTest` | leaderboard record-on-resolve, percentile in response, `GET /scenarios/{slug}/leaderboard` |
| `ScenarioFlowIntegrationTest` | public catalog/detail read paths |
| `UserFlowIntegrationTest` | attempt history — ordering, scoping to the caller, page-size cap |
| `LeaderboardServiceTest` | upsert logic (higher score wins, same score better time wins), percentile calculation (unit-level) |
| `ScenarioValidationServiceTest` | publish-gate validation rules (unit-level, no Spring context) |
| `VerificationCodeServiceTest` | resend cooldown enforcement — within-window throws 429, after-window succeeds (unit-level) |

All integration tests connect to a local MongoDB instance at `localhost:27017` (see the class-level
doc comment in `AuthFlowIntegrationTest` for why Testcontainers is not used here and how to swap
back when running in a Linux CI environment where Docker's API works correctly).

## Package layout

```
com.incidentroom
├── IncidentRoomApplication
├── config/       @Configuration classes — SecurityConfig (filter chains), OAuth2ClientRegistrationConfig,
│                 CacheConfig, WebConfig, OpenApiConfig
├── security/     Everything that enforces or implements a security control:
│                 JwtAuthenticationFilter, CookieService, JwtService, RateLimitFilter,
│                 CustomOAuth2UserService, OAuth2AuthenticationSuccessHandler,
│                 OAuth2AuthenticationFailureHandler, CurrentUserResolver,
│                 AdminNotFoundEntryPoint / AdminNotFoundAccessDeniedHandler (404 masking),
│                 ApiAuthenticationEntryPoint / ApiAccessDeniedHandler (JSON 401/403)
├── domain/       Persisted MongoDB documents + enums — AppUser, RefreshToken, EmailVerification,
│                 Scenario (+ Topology/ActionDefinition/Outcome/TelemetryEvent/ScenarioSeo),
│                 Attempt (+ AttemptAction), LeaderboardEntry, and their enums
├── repository/   Spring Data MongoDB repositories (including LeaderboardRepository)
├── service/      Business logic — AuthService, RefreshTokenService, VerificationCodeService
│                 (resend cooldown enforced), EmailService (+ ConsoleEmailService for local dev,
│                 ResendEmailService for prod), ScenarioService, ScenarioValidationService,
│                 ScenarioYamlImportService, AttemptService, LeaderboardService
├── controller/   REST controllers — AuthController, UserController, ScenarioController
│                 (+ GET /scenarios/{slug}/leaderboard), AdminScenarioController, AttemptController
├── dto/          Request/response records — the enforcement boundary for what leaves the API;
│                 includes LeaderboardEntryDto, LeaderboardResponseDto, and percentile field on
│                 ResolveAttemptResponse
└── common/       Cross-cutting, not really a "layer" — exception handling + logging.
    ├── exception/      ApiException family + GlobalExceptionHandler
    └── logging/        CorrelationIdFilter
```

Note on `common/`: the requested layout was `config/ security/ domain/ repository/ service/
controller/ dto/`, which this follows exactly for every feature class. `common/` holds the two
things that don't belong to any single layer — request-scoped error handling and logging — and
exists alongside those seven rather than replacing any of them.