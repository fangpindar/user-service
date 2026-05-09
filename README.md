# User Service

A Spring Boot member-service API featuring email-based registration with **two-phase activation**, **2FA email OTP login**, refresh-token rotation, and a self-service `/me/last-login` endpoint.

Built as an interview deliverable demonstrating production-style backend practices: Flyway migrations, Redis-backed rate limiting, BCrypt password hashing, OTP brute-force protection, JWT access + refresh token rotation with blacklist, and integration tests on real Postgres + Redis via Testcontainers.

---

## Online Demo

> **Swagger UI:** `http://<ec2-public-dns>/swagger-ui.html`

The deployed instance runs on AWS EC2 (Amazon Linux 2023, t2.micro free tier) using docker-compose. URL is the EC2 default DNS name; HTTP only (production would terminate TLS at ALB / Caddy / Nginx with a real domain + ACM cert).

---

## Quick Start (Local)

### Prerequisites
- Docker + Docker Compose
- (Optional, for direct dev) Java 21, Maven 3.9+

### Run with docker-compose
```bash
git clone <this-repo>
cd <repo>
cp .env.example .env

# Edit .env. The only thing you MUST change for activation/OTP emails to actually
# arrive in your inbox is SENDGRID_API_KEY (otherwise emails are logged to stdout —
# which is fine for testing, just check `docker compose logs app`).
$EDITOR .env

docker compose up -d
docker compose logs -f app    # wait for "Started UserServiceApplication"
```

Open Swagger:
- http://localhost/swagger-ui.html

### Run tests
```bash
mvn test
```
Integration tests use Testcontainers — Docker must be running.

---

## API (9 endpoints)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/v1/auth/register` | – | Register, status PENDING_ACTIVATION, send activation email |
| POST | `/api/v1/auth/resend-activation` | – | Resend activation email (rate-limited) |
| GET  | `/api/v1/auth/activate?token=xxx` | – | Show confirmation page (HTML, no side effect) |
| POST | `/api/v1/auth/activate` | – | Actually activate the account |
| POST | `/api/v1/auth/login` | – | Phase 1: validate credentials, send OTP |
| POST | `/api/v1/auth/verify-otp` | – | Phase 2: verify OTP, issue JWT pair |
| POST | `/api/v1/auth/refresh` | refresh | Rotate token pair |
| POST | `/api/v1/auth/logout` | bearer | Blacklist access, revoke all refresh |
| GET  | `/api/v1/users/me/last-login` | bearer | Return caller's last login (taken from JWT — no `/users/{id}` form) |

Full request/response schemas: open the Swagger UI.

---

## Two-Phase Activation (defeats email link scanners)

```
POST /register
        ↓ user record created (PENDING_ACTIVATION)
        ↓ activation_token row stored
        ↓ SendGrid email sent with link to /activate?token=xxx
        
User clicks link in email
        ↓
GET /activate?token=xxx        ← server validates token READ-ONLY
        ↓ returns HTML confirmation page with [Activate] button
        ↓
User clicks [Activate]
        ↓
POST /activate { token }       ← server flips status=ACTIVE, marks token used
```

**Why two phases?** Corporate email security (Outlook Safe Links, Gmail Preview, Mimecast) auto-fetches links. If activation happened on `GET`, scanners would silently activate accounts. The `GET` endpoint is purely a confirmation page; the actual state change happens on `POST` triggered by a real human click.

---

## Two-Factor Login

```
POST /login {email, password}
        ↓ Redis check: login_fail:{email} >= 5 → 423 Locked
        ↓ BCrypt compare password
        ↓ generate 6-digit OTP, BCrypt-hash it
        ↓ Redis: SET otp:{challengeId} {userId, codeHash, attempts:0} EX 300
        ↓ SendGrid email with OTP code
        ↓ returns { challengeId, expiresInSeconds: 300 }

POST /verify-otp { challengeId, code }
        ↓ Redis: HGETALL otp:{challengeId}
        ↓ if attempts >= 3 → DEL key → 401
        ↓ if BCrypt mismatch → HINCRBY attempts → 401
        ↓ DEL otp:{challengeId}, DEL login_fail:{email}
        ↓ INSERT login_audit; UPDATE users.last_login_at
        ↓ issue access JWT (15m) + refresh JWT (7d)
        ↓ Redis: SET refresh:{userId}:{jti} 1 EX 604800
        ↓ returns { accessToken, refreshToken, expiresInSeconds: 900 }
```

Brute-force bound: 6-digit OTP (1 in 1,000,000) × 3 attempts/challenge × 5 challenges/15min lockout = at most 15 guesses per 15 minutes per email.

---

## Refresh Token Rotation

`POST /refresh { refreshToken }`:
1. Verify JWT signature with the **refresh** secret (separate from access secret).
2. Look up `refresh:{userId}:{jti}` in Redis. If missing → already used or revoked → 401.
3. **Delete the old key immediately** — replaying that refresh token will now fail.
4. Issue a new access + new refresh (with fresh jti).
5. Store the new refresh key, return both tokens.

The integration test [`AuthFlowIntegrationTest.happyPath`](src/test/java/com/example/userservice/integration/AuthFlowIntegrationTest.java) exercises this: replay of the consumed refresh token returns 401.

---

## Logout & Access Token Blacklist

`POST /logout` (with Bearer access token):
1. Parse access JWT → extract `jti` and `exp`.
2. `SET blacklist:{jti} 1 EX (exp - now)` — auto-expires when the token would have expired anyway.
3. `SCAN refresh:{userId}:* | DEL` — wipes all refresh tokens for the user (logout everywhere).

Every authenticated request goes through `JwtAuthenticationFilter`, which after parsing the token does `EXISTS blacklist:{jti}` and rejects if found.

---

## Limits & TTLs

| Setting | Value | Backed by |
|---|---|---|
| Activation token TTL | 24h | `activation_tokens.expires_at` |
| Resend activation cooldown | 60s per email | Redis `resend_cd:{email}` |
| Resend activation daily cap | 5 / 24h | Redis `resend_count:{email}` |
| Password failure lock | 5 fails → 15 min | Redis `login_fail:{email}` |
| OTP TTL | 5 min | Redis `otp:{challengeId}` |
| OTP attempts | 3 / challenge | Redis hash field `attempts` |
| Access JWT TTL | 15 min | JWT `exp` claim |
| Refresh JWT TTL | 7 days | Redis `refresh:{userId}:{jti}` |

All values configurable via `application.yml` / env vars.

---

## Environment Variables

| Var | Required | Description |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | yes | Postgres connection |
| `REDIS_HOST`, `REDIS_PORT` | yes | Redis connection |
| `JWT_ACCESS_SECRET` | yes | ≥32 bytes random; `openssl rand -base64 48` |
| `JWT_REFRESH_SECRET` | yes | Different ≥32 bytes random |
| `SENDGRID_API_KEY` | optional | If empty, emails are logged to stdout (`LoggingEmailService`); if set, real SendGrid send |
| `EMAIL_FROM` | yes | Sender address (must be a SendGrid Verified Sender if real send) |
| `APP_BASE_URL` | yes | Public URL (e.g. `http://ec2-x.compute.amazonaws.com`); becomes the prefix in activation email links |

---

## Project Layout

```
src/main/java/com/example/userservice/
├── auth/        registration, activation, login, OTP, JWT, refresh, logout
├── user/        User entity, /me/last-login
├── email/       SendGrid + LoggingEmailService (interface-based, easy to mock in tests)
├── ratelimit/   Redis-backed rate limit primitives
├── config/      Security, Redis, OpenAPI, properties classes
└── common/      Global exception handler, ApiError, custom domain exceptions
src/main/resources/
├── application.yml
└── db/migration/V1..V4__*.sql           Flyway versioned schema
src/test/java/com/example/userservice/
├── integration/AuthFlowIntegrationTest  9 end-to-end scenarios
└── support/                             InMemoryEmailService, IntegrationTestBase
```

---

## Design Decisions (and why)

- **Two-phase activation (GET → POST)** — defeats email link scanners that auto-fetch URLs.
- **Refresh token as JWT (not opaque UUID)** — server can verify the signature and extract `userId` *before* a Redis lookup, so the Redis key structure can be `refresh:{userId}:{jti}` (per-user namespacing makes "logout-all-devices" a simple `SCAN`).
- **OTP stored as BCrypt hash** — even a Redis dump doesn't leak active OTPs.
- **Both `users.last_login_at` (denormalised) and `login_audit` (full history)** — the API uses the column for O(1) reads; the audit table preserves a real record for security investigations.
- **Email enumeration defence** — `/login` returns the same 401 for "user not found" and "wrong password"; `/resend-activation` returns the same 200 for unknown / already-active / pending users, so the endpoint can't be used to harvest registered emails.
- **HTML inline in controller, not Thymeleaf** — the activation confirmation page is two screens of HTML; Thymeleaf would be more dependencies for negligible benefit.
- **`LoggingEmailService` fallback** — lets you run the project end-to-end without a SendGrid account; codes/links print to `docker compose logs app`.

---

## Deploying to AWS EC2

### Create instance
- AMI: Amazon Linux 2023
- Size: t2.micro (free tier; t3.micro also fine)
- Storage: 20 GB gp3 (free tier covers up to 30 GB)
- Region: ap-northeast-1 (Tokyo) recommended for low latency from TW
- (Optional) Allocate an Elastic IP and associate it — free tier includes 1 EIP **as long as it's attached** to a running instance

### Security Group
| Direction | Port | Source |
|---|---|---|
| Inbound | 22 (SSH) | your IP only |
| Inbound | 80 (HTTP) | 0.0.0.0/0 |
| Outbound | all | 0.0.0.0/0 |

### On the instance
```bash
sudo dnf install -y docker git
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user
exit                          # re-SSH so the new group takes effect
ssh ec2-user@<your-ec2>

# Install Docker Compose plugin
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# Clone & configure
git clone https://github.com/<you>/<repo>.git
cd <repo>
cp .env.example .env

# Edit .env — must set:
#   APP_BASE_URL=http://<ec2-public-dns>      (used in activation email links)
#   JWT_ACCESS_SECRET, JWT_REFRESH_SECRET     (openssl rand -base64 48)
#   SENDGRID_API_KEY, EMAIL_FROM              (SendGrid Verified Sender)
#   DB_PASSWORD                                (something not "changeme")
$EDITOR .env

docker compose up -d
docker compose logs -f app    # wait for "Started UserServiceApplication"
```

Open Swagger from your laptop:
```
http://<ec2-public-dns>/swagger-ui.html
```

### Notes
- The container maps host port 80 → app container port 8080 (`docker-compose.yml`), so the URL has no port suffix.
- This is HTTP only. For production you'd put Caddy or Nginx in front with Let's Encrypt (requires a real domain you control, which the EC2 default DNS does not satisfy).

---

## Troubleshooting

**No activation/OTP email received:**
- Check `docker compose logs app` — if `LoggingEmailService` fired, you'll see the link/code printed there. Set `SENDGRID_API_KEY` and a SendGrid Verified Sender for `EMAIL_FROM` to enable real email.
- SendGrid free tier sometimes routes to spam; check your spam folder.

**Activation link in email points to `localhost`:**
- `APP_BASE_URL` in `.env` is wrong. On EC2 it must be `http://<ec2-public-dns>`. Restart with `docker compose down && docker compose up -d` after editing.

**`mvn test` fails with Testcontainers errors:**
- Make sure Docker Desktop is running. Tests pull `postgres:16-alpine` and `redis:7-alpine` images on first run.

---

## Known Limitations / Future Work

- HTTP-only deployment (no TLS) — interview demo simplicity. Production would terminate TLS at a reverse proxy with ACM/Let's Encrypt cert on a real domain.
- No CI/CD pipeline (intentionally out of scope).
- Single-instance deployment — for HA you'd need multiple app instances behind a load balancer, plus Redis replicas.
- No password reset flow.
- No admin/staff endpoints (none required by the spec; `/me/*` is intentionally the only personal-data endpoint).
