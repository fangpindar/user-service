# User Service

一個會員服務 API，提供 email 註冊、**雙階段啟用**、**Email OTP 兩階段認證登入**、Refresh Token Rotation、以及自助查詢「最後登入時間」的端點。

採用 production-style 的後端工程實務：Flyway 資料庫 migration、Redis-backed rate limiting、BCrypt 密碼雜湊、OTP 暴力破解防護、JWT access + refresh token rotation 與 blacklist，並用 Testcontainers 在真實 PostgreSQL + Redis 上跑整合測試。

---

## 線上 Demo

> **Swagger UI**：http://ec2-54-178-81-61.ap-northeast-1.compute.amazonaws.com/swagger-ui.html

部署於 AWS EC2（Amazon Linux 2023, t2.micro free tier）使用 docker-compose。URL 為 EC2 default DNS；HTTP only（production 會在 ALB / Caddy / Nginx 終止 TLS，並使用真實 domain + ACM 憑證）。

---

## 快速開始（本機）

### 前置需求
- Docker + Docker Compose
- （選用，直接開發用）Java 21、Maven 3.9+

### 用 docker-compose 啟動
```bash
git clone <this-repo>
cd <repo>
cp .env.example .env

# 編輯 .env。要讓啟用信、OTP 信真的進到信箱，必須設 SENDGRID_API_KEY
# （否則信會印到 stdout，檢查 docker compose logs app 也行，這對測試已足夠）
$EDITOR .env

docker compose up -d
docker compose logs -f app    # 等到 "Started UserServiceApplication"
```

打開 Swagger：
- http://localhost/swagger-ui.html

### 跑測試
```bash
mvn test
```
整合測試使用 Testcontainers，請確認 Docker 已啟動。

---

## API（共 9 個端點）

| Method | Path | Auth | 用途 |
|---|---|---|---|
| POST | `/api/v1/auth/register` | – | 註冊（建立 PENDING_ACTIVATION user，寄啟用信） |
| POST | `/api/v1/auth/resend-activation` | – | 重寄啟用信（rate-limited） |
| GET  | `/api/v1/auth/activate?token=xxx` | – | 顯示啟用確認頁面（HTML，無副作用） |
| POST | `/api/v1/auth/activate` | – | 真正啟用帳號 |
| POST | `/api/v1/auth/login` | – | Phase 1：驗帳密、寄 OTP |
| POST | `/api/v1/auth/verify-otp` | – | Phase 2：驗 OTP、發 JWT pair |
| POST | `/api/v1/auth/refresh` | refresh | Token rotation |
| POST | `/api/v1/auth/logout` | bearer | 登出（黑名單 access、清空 refresh） |
| GET  | `/api/v1/users/me/last-login` | bearer | 查自己最後登入時間（從 JWT 解 userId，無 `/users/{id}` 入口） |

完整 request/response schema：請開 Swagger UI 查看。

---

## 雙階段啟用（防 email 連結掃描器）

```
POST /register
        ↓ 建立 user (PENDING_ACTIVATION)
        ↓ 寫 activation_token row
        ↓ SendGrid 寄信，內含 /activate?token=xxx 連結

使用者點 email 連結
        ↓
GET /activate?token=xxx        ← server 驗 token READ-ONLY
        ↓ 回含「Activate」按鈕的 HTML 頁面
        ↓
使用者點「Activate」按鈕
        ↓
POST /activate { token }       ← server 把 status 翻成 ACTIVE，token 標記 used
```

**為什麼要分兩階段？** 企業 email 安全產品（Outlook Safe Links、Gmail 預覽、Mimecast）會自動 fetch 連結。如果啟用直接做在 `GET`，掃描器會無聲無息地啟用帳號。`GET` 純粹顯示確認頁；實際狀態變更只發生在 `POST`（由真人按下按鈕觸發）。

---

## 兩階段認證登入

```
POST /login {email, password}
        ↓ Redis 檢查：login_fail:{email} >= 5 → 423 Locked
        ↓ BCrypt 比對密碼
        ↓ 產生 6 位 OTP，BCrypt-hash 後存進 Redis
        ↓ Redis: SET otp:{challengeId} {userId, codeHash, attempts:0} EX 300
        ↓ SendGrid 寄 OTP 信
        ↓ 回 { challengeId, expiresInSeconds: 300 }

POST /verify-otp { challengeId, code }
        ↓ Redis: HGETALL otp:{challengeId}
        ↓ if attempts >= 3 → DEL key → 401
        ↓ if BCrypt 不符 → HINCRBY attempts → 401
        ↓ DEL otp:{challengeId}, DEL login_fail:{email}
        ↓ INSERT login_audit; UPDATE users.last_login_at
        ↓ 簽 access JWT (15 分鐘) + refresh JWT (7 天)
        ↓ Redis: SET refresh:{userId}:{jti} 1 EX 604800
        ↓ 回 { accessToken, refreshToken, expiresInSeconds: 900 }
```

**暴力破解上限**：6 位 OTP（百萬分之一） × 3 次/challenge × 5 challenges/15 分鐘 lockout
= 每個 email 每 15 分鐘最多 15 次嘗試，攻擊不可行。

---

## Refresh Token Rotation

`POST /refresh { refreshToken }`：
1. 用 **refresh** secret 驗證 JWT 簽章（與 access secret 分開）。
2. Redis 查 `refresh:{userId}:{jti}`。不存在 → 已使用或撤銷 → 401。
3. **立刻 DEL 舊 key** —— 該 refresh 重放會失敗。
4. 簽新的 access + 新的 refresh（新 jti）。
5. 寫入新的 refresh key，回傳新的 token pair。

整合測試 [`AuthFlowIntegrationTest.happyPath`](src/test/java/com/example/userservice/integration/AuthFlowIntegrationTest.java) 驗證了這個行為：消費過的 refresh token 重放會回 401。

---

## 登出與 Access Token 黑名單

`POST /logout`（帶 Bearer access token）：
1. 解析 access JWT → 取 `jti` 與 `exp`。
2. `SET blacklist:{jti} 1 EX (exp - now)` —— 等 token 自然到期就自動清掉。
3. `SCAN refresh:{userId}:* | DEL` —— 清除該 user 所有 refresh token（多裝置一次踢光）。

每個 authenticated 請求經過 `JwtAuthenticationFilter` 時，解析 token 後都會 `EXISTS blacklist:{jti}`，存在即拒絕。

---

## 限制與時效

| 設定 | 值 | 由誰管 |
|---|---|---|
| Activation token TTL | 24 小時 | `activation_tokens.expires_at` |
| Resend activation 冷卻 | 60 秒/email | Redis `resend_cd:{email}` |
| Resend activation 每日上限 | 5 / 24h | Redis `resend_count:{email}` |
| 密碼失敗鎖定 | 5 次 → 鎖 15 分鐘 | Redis `login_fail:{email}` |
| OTP TTL | 5 分鐘 | Redis `otp:{challengeId}` |
| OTP 嘗試上限 | 3 次/challenge | Redis hash field `attempts` |
| Access JWT TTL | 15 分鐘 | JWT `exp` claim |
| Refresh JWT TTL | 7 天 | Redis `refresh:{userId}:{jti}` |

所有值可透過 `application.yml` / 環境變數調整。

---

## 環境變數

| Var | 必要 | 說明 |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | yes | Postgres 連線 |
| `REDIS_HOST`, `REDIS_PORT` | yes | Redis 連線 |
| `JWT_ACCESS_SECRET` | yes | ≥32 bytes 隨機字串；`openssl rand -base64 48` |
| `JWT_REFRESH_SECRET` | yes | 另一個獨立 ≥32 bytes 隨機字串 |
| `SENDGRID_API_KEY` | optional | 為空：用 `LoggingEmailService` 印到 stdout；有設：真寄信 |
| `EMAIL_FROM` | yes | 寄件人地址（必須是 SendGrid Verified Sender） |
| `APP_BASE_URL` | yes | 公開 URL（如 `http://ec2-x.compute.amazonaws.com`），會成為啟用信連結的 prefix |

---

## 專案結構（按 feature 分層 + interface/impl 分離）

```
src/main/java/com/example/userservice/
├── auth/
│   ├── controller/      AuthController, ActivationController
│   ├── dto/             所有 request / response DTO（Java records）
│   ├── jwt/             JwtAuthenticationFilter, JwtTokenProvider, UserPrincipal
│   ├── service/         7 個 interface（RegistrationService, ActivationService,
│   │                    LoginService, OtpService, TokenService, LogoutService,
│   │                    ResendActivationService）
│   └── service/impl/    @Service 實作類別（命名 *Impl）
│
├── user/
│   ├── controller/      UserController（GET /me/last-login）
│   ├── dto/             LastLoginResponse
│   ├── entity/          User, UserStatus, ActivationToken, LoginAudit
│   ├── repository/      UserRepository, ActivationTokenRepository, LoginAuditRepository
│   ├── service/         UserQueryService（interface）
│   └── service/impl/    UserQueryServiceImpl
│
├── email/
│   ├── service/         EmailService（interface）
│   ├── service/impl/    SendGridEmailService, LoggingEmailService
│   └── template/        ActivationEmailTemplate, OtpEmailTemplate
│
├── ratelimit/
│   ├── service/         RateLimitService（interface）
│   └── service/impl/    RateLimitServiceImpl
│
├── common/
│   ├── exception/       GlobalExceptionHandler, ApiError
│   │   └── domain/      自訂 exception（AppException 抽象基底 + 7 個子類）
│   └── util/            SecureTokenGenerator
│
└── config/              SecurityConfig, RedisConfig, OpenApiConfig, EmailConfig
    └── properties/      JwtProperties, AppProperties

src/main/resources/
├── application.yml
└── db/migration/V1..V4__*.sql            Flyway 版本化 schema

src/test/java/com/example/userservice/
├── integration/AuthFlowIntegrationTest    9 個端對端場景
└── support/                               InMemoryEmailService, IntegrationTestBase
```

**結構原則**：
- 每個 feature package（`auth/`、`user/`、`email/`、`ratelimit/`）內部都採用 `controller/` + `service/` + `service/impl/` 的一致組織。
- Service 一律先定義 interface，實作類別放 `service/impl/` 並以 `*Impl` 命名 ——  日後要替換實作（如把 SendGrid 換成 SES）只動 impl 不影響 caller。
- Entity 與 Repository 只在 `user/` 出現（DDD 上他們屬於 User aggregate）。
- 用 record 帶在 interface 裡（如 `TokenService.TokenPair`、`OtpService.Issued`），不用另外建 dto package 放 service 內部使用的小資料載體。

---

## 設計決策（為什麼這樣選）

- **雙階段啟用（GET → POST）** —— 防 email 掃描器自動 fetch 連結造成帳號被誤觸啟用。
- **Refresh token 用 JWT，不用 opaque UUID** —— server 可以先用 refresh secret 驗簽章解出 `userId`，再去 Redis 查；這樣 Redis key 結構可以是 `refresh:{userId}:{jti}`（每個 user 獨立 namespace），實作「全裝置登出」就只需一個 `SCAN`。
- **OTP 存 BCrypt hash** —— 即使 Redis 被 dump 也不會直接洩漏正在生效的 OTP。
- **同時保留 `users.last_login_at`（denormalized）與 `login_audit`（完整歷史）** —— API 用 column 做 O(1) 讀取；audit table 保留實際紀錄供安全調查或未來 login history feature。
- **Email enumeration 防護** —— `/login` 對「使用者不存在」與「密碼錯誤」回同個 401；`/resend-activation` 對未知 / 已啟用 / PENDING 都回同個 200，所以無法當作工具來蒐集已註冊的 email。
- **Activation 確認頁直接 inline 在 controller，不引入 Thymeleaf** —— 該頁只有兩螢幕的 HTML，引入模板引擎不划算。
- **`LoggingEmailService` fallback** —— 讓專案在沒 SendGrid 帳號的環境也能完整跑；連結與 OTP code 都會印到 `docker compose logs app`。
- **按 feature 分層 + interface/impl 分離** —— 同一個 feature 的 controller / service / repository 集中在一起易追蹤；interface 與 impl 分離讓未來換實作或寫測試 mock 都更乾淨。

---

## 部署到 AWS EC2

### 一鍵部署（推薦）

`scripts/` 下有兩支腳本自動化整個流程：

```bash
# Provision EC2 + security group + key pair，然後 docker compose up。
# 冪等 —— git push 後重跑會就地 redeploy 同一個 instance。
./scripts/aws-deploy.sh

# Demo 結束後拆掉所有資源，避免超出 free tier 計費。
./scripts/aws-teardown.sh
```

**前置需求**：
- AWS CLI 已 configured（`aws sts get-caller-identity` 能成功）
- `jq` 已安裝（`brew install jq`）
- 本機 `.env` 已填好（腳本會在動 AWS 之前先驗證所有必要值）

**預設值**（可用環境變數覆蓋）：

| Var | 預設值 |
|---|---|
| `AWS_REGION` | `ap-northeast-1` |
| `INSTANCE_TYPE` | `t2.micro` |
| `PROJECT_NAME` | `denden-user-service` |
| `GITHUB_REPO` | `https://github.com/fangpindar/user-service.git` |
| `GITHUB_BRANCH` | `main` |

**`aws-deploy.sh` 做了什麼**：
1. 驗證 `.env` 包含真實值（非 placeholder）：`SENDGRID_API_KEY`、JWT secrets、`DB_PASSWORD` 等。
2. 建立 SSH key pair（私鑰存於 `~/.ssh/<project>-key.pem`）。
3. 建立 security group：SSH (22) **只開給您當前的公網 IP**，HTTP (80) 全網開放。
4. 找最新 Amazon Linux 2023 AMI，啟動 `t2.micro` instance。
5. 安裝 Docker + git + buildx，git clone repo，scp `.env`（會自動把 `APP_BASE_URL` 改成 EC2 public DNS），執行 `docker compose up -d`。
6. 輪詢 `/actuator/health` 直到 `UP`，印出 Swagger URL。

push 到 GitHub 後重跑這支腳本，會在 instance 上 git pull 並 `docker compose up --build`。迭代只需一個指令。

### 手動部署（不想用腳本時）

#### 建立 instance
- AMI：Amazon Linux 2023
- 大小：t2.micro（free tier；t3.micro 也可以）
- 儲存：20 GB gp3（free tier 涵蓋 30 GB 以下）
- Region：ap-northeast-1（東京，TW 延遲低）
- （選用）申請 Elastic IP 並 attach —— free tier 有 1 個 EIP **只要綁在執行中的 instance** 就免費

#### Security Group
| 方向 | Port | 來源 |
|---|---|---|
| Inbound | 22（SSH） | 您本機 IP |
| Inbound | 80（HTTP） | 0.0.0.0/0 |
| Outbound | 全部 | 0.0.0.0/0 |

#### 在 instance 上執行
```bash
sudo dnf install -y docker git
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user
exit                          # 重新 SSH 讓新 group 生效
ssh ec2-user@<your-ec2>

# 安裝 Docker Compose plugin
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# 安裝 buildx（compose v2 需要）
sudo curl -SL https://github.com/docker/buildx/releases/download/v0.18.0/buildx-v0.18.0.linux-amd64 \
  -o /usr/local/lib/docker/cli-plugins/docker-buildx
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-buildx

# Clone & 設定
git clone https://github.com/<you>/<repo>.git
cd <repo>
cp .env.example .env

# 編輯 .env —— 必須設定：
#   APP_BASE_URL=http://<ec2-public-dns>     （啟用信連結會用）
#   JWT_ACCESS_SECRET, JWT_REFRESH_SECRET    （openssl rand -base64 48）
#   SENDGRID_API_KEY, EMAIL_FROM             （SendGrid Verified Sender）
#   DB_PASSWORD                              （非 "changeme"）
$EDITOR .env

docker compose up -d
docker compose logs -f app    # 等 "Started UserServiceApplication"
```

從筆電打開 Swagger：
```
http://<ec2-public-dns>/swagger-ui.html
```

#### 注意事項
- Container 把 host port 80 對到 app container port 8080（`docker-compose.yml`），所以 URL 不需要帶 port number。
- 純 HTTP（無 TLS）。Production 會用 Caddy / Nginx + Let's Encrypt（需要您擁有的真實 domain，EC2 default DNS 拿不到憑證）。
