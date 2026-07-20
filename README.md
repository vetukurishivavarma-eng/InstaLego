# InstaLego 🧱

> **Legal Document → Legal Opinion Generator**
>
> Sign in, upload a case's legal documents, and submit them for a structured Legal Opinion —
> in a bank's own format when one has been configured, or InstaLego's standard format otherwise.
> Fully open-source LLMs under the hood (via Groq), so there's no proprietary model dependency.

---

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌──────────────┐
│  React/Vite  │────▶│  Spring Boot  │────▶│  PostgreSQL   │
│  Frontend    │     │  REST API    │     │  (or H2)     │
│  port 5173   │◀────│  port 8080   │◀────│              │
└─────────────┘     └──────┬───────┘     └──────────────┘
                           │
                    ┌──────▼───────┐     ┌──────────────┐
                    │  Groq API     │     │  Local Disk   │
                    │  (open-weight │     │  /uploads     │
                    │  models only) │     │              │
                    └──────────────┘     └──────────────┘
```

- **Backend**: Java 17 + Spring Boot 3.x (REST API), Spring Security + JWT auth
- **Frontend**: React 18 + Vite + TypeScript
- **Database**: H2 (local dev) / PostgreSQL (production)
- **LLM**: Groq-hosted open-weight models only — `openai/gpt-oss-120b` for legal reasoning,
  `meta-llama/llama-4-scout-17b-16e-instruct` for vision/OCR on scanned documents. No proprietary
  model APIs are used anywhere in the pipeline.
- **PDF**: Apache PDFBox 3.x — renders the finished analysis as a formatted "Legal Opinion" PDF
- **Auth**: Email/password with JWT. Two roles: `USER` (submit documents, view own history) and
  `ADMIN` (also manages banks and their legal-opinion formats)

---

## Why Groq / open-weight models

The requirement is an **open-source model that won't choke on large files and stays fast**.
Groq hosts open-weight models (Meta Llama, OpenAI's Apache-2.0 `gpt-oss` line, Qwen) on its own
inference hardware, which is both:
- **Open-source**: the model weights themselves are open-weight, not a closed proprietary API.
- **Fast**: Groq's LPU inference is dramatically faster than typical GPU-hosted inference — this
  is what keeps the app responsive instead of self-hosting a model locally (which would be slow
  and memory-hungry on ordinary hardware).

To guarantee a 30MB document can never exhaust the model's context window, the backend estimates
token usage before every call. Small document sets go through in a single pass; anything large is
automatically split into batches (map phase — extract structured facts per batch) and merged in a
final reduce call — so correctness holds regardless of file size instead of relying on hoping it
fits.

Scanned/image-only documents are OCR'd by rasterizing each page and sending it to Groq's
vision-capable open-weight model (`llama-4-scout`) — no proprietary OCR/vision API is used.

---

## Prerequisites

| Tool       | Version   | Install                                  |
|------------|-----------|-------------------------------------------|
| Java       | 17+       | [Adoptium](https://adoptium.net/)        |
| Node.js    | 18+       | [nodejs.org](https://nodejs.org/)        |
| Maven      | 3.9+      | Included via Maven wrapper (see below)   |
| Git        | any       | [git-scm.com](https://git-scm.com/)      |

---

## Getting a Groq API Key (Free)

1. Go to [console.groq.com](https://console.groq.com/keys)
2. Create an API key — it starts with `gsk_...`
3. Groq's free tier is generous and fast enough for development and light production use

---

## Local Setup

### 1. Clone & Navigate

```bash
git clone <your-repo-url>
cd InstaLego
```

### 2. Set environment variables

**Windows (PowerShell)**:
```powershell
$env:GROQ_API_KEY="gsk_..."
$env:JWT_SECRET="a-long-random-string-at-least-32-chars"
$env:ADMIN_EMAIL="admin@example.com"
$env:ADMIN_PASSWORD="choose-a-strong-password"
```

**macOS / Linux**:
```bash
export GROQ_API_KEY=gsk_...
export JWT_SECRET="a-long-random-string-at-least-32-chars"
export ADMIN_EMAIL=admin@example.com
export ADMIN_PASSWORD=choose-a-strong-password
```

`ADMIN_EMAIL`/`ADMIN_PASSWORD` seed exactly one admin account on first boot — this is the only way
to get an `ADMIN` account (registration always creates a `USER`). If you skip them, the app still
runs, but there's no way to manage banks/legal-opinion formats until you set them and restart.

`JWT_SECRET` has an insecure dev-only fallback baked in — always set a real one outside local dev.

### 3. Run the Backend

The backend uses H2 (embedded file-based database) by default — no PostgreSQL setup needed for
local development.

```bash
cd backend

# Option A: With Maven installed
mvn spring-boot:run

# Option B: Using Maven wrapper (auto-downloads Maven)
./mvnw spring-boot:run
```

The API will be available at **http://localhost:8080**

- H2 Console: http://localhost:8080/h2-console
  - JDBC URL: `jdbc:h2:file:./data/instalego`
  - Username: `sa`
  - Password: *(blank)*

> If you're updating an existing local checkout from before auth existed, delete
> `backend/data/instalego*` once before the first run — the schema gained a `users` table and a
> foreign key on verification jobs.

**For production (PostgreSQL/Neon)**:
```bash
export DB_HOST=...
export DB_NAME=...
export DATABASE_USERNAME=...
export DATABASE_PASSWORD=...
cd backend && mvn spring-boot:run -Dspring.profiles.active=prod
```

### 4. Run the Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend will be available at **http://localhost:5173**

It proxies `/api` requests to the backend at `http://localhost:8080`.

---

## Usage Guide

### Sign in

Everything requires an account. Register a `USER` account at `/register`, or sign in as the
seeded `ADMIN` account to manage banks.

### Admin Panel (`/admin`, ADMIN role only)

1. **Add a Bank**: Enter a bank name (e.g., "HDFC Bank") and click "Add Bank"
2. **Upload a Legal Opinion Format** (optional): Upload a sample opinion PDF showing how this bank
   wants output formatted. The open-source model analyzes it and derives its structure. If skipped,
   InstaLego's standard legal-opinion structure is used instead.
3. **Upload Legal References** (optional): Bank policies, regulatory guidelines, or legal
   requirements the verification should cross-reference against.

### Submit for Legal Opinion (`/`)

1. **Select a Bank**
2. **Upload Documents**: PDF, JPG, PNG, DOCX, or TXT (up to 30MB each)
3. **Submit for Legal Opinion**: processes asynchronously — the page polls for status and shows a
   live analysis log
4. **If documents are missing**: any document referenced inside your files but not uploaded (e.g. a
   prior sale deed) is flagged — upload it and continue
5. **Review & Download**: the finished opinion renders on-screen; click **Download Legal Opinion
   (PDF)** for a formatted document

---

## API Endpoints

| Method | Endpoint                              | Auth        | Description                          |
|--------|----------------------------------------|-------------|---------------------------------------|
| POST   | `/api/auth/register`                  | Public      | Create a USER account                |
| POST   | `/api/auth/login`                     | Public      | Log in, returns a JWT                |
| GET    | `/api/auth/me`                        | Any user    | Current user info                    |
| POST   | `/api/banks`                          | ADMIN       | Create a new bank                    |
| GET    | `/api/banks`                          | Any user    | List all banks                       |
| GET    | `/api/banks/with-template`            | Any user    | List banks available for submission  |
| POST   | `/api/banks/{bankId}/report-format`   | ADMIN       | Upload sample opinion PDF, derive structure |
| GET    | `/api/banks/{bankId}/report-format`   | Any user    | Get a bank's opinion format info     |
| DELETE | `/api/banks/{bankId}/report-format`   | ADMIN       | Delete a bank's opinion format       |
| POST   | `/api/banks/{bankId}/references`      | Any user    | Upload a legal reference document    |
| GET    | `/api/banks/{bankId}/references`      | Any user    | List a bank's legal references       |
| DELETE | `/api/banks/{bankId}/references/{id}` | Any user    | Delete a legal reference             |
| POST   | `/api/verify/start`                   | Any user    | Start a submission session           |
| POST   | `/api/verify/{id}/add-document`       | Owner/ADMIN | Attach a document to a session       |
| POST   | `/api/verify/{id}/run`                | Owner/ADMIN | Run/re-run the legal opinion analysis|
| GET    | `/api/verify/{id}`                    | Owner/ADMIN | Poll session status & report         |
| GET    | `/api/verify/{id}/opinion.pdf`        | Owner/ADMIN | Download the formatted opinion PDF   |
| GET    | `/api/verify/mine`                    | Any user    | List the current user's submissions  |

---

## Project Structure

```
InstaLego/
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/instalego/
│       ├── InstaLegoApplication.java
│       ├── config/
│       │   ├── AsyncConfig.java         — Async executor for job processing
│       │   ├── WebConfig.java           — CORS + static resources
│       │   ├── SecurityConfig.java      — Spring Security filter chain
│       │   ├── AdminSeeder.java         — Seeds the ADMIN account from env vars
│       │   └── SchemaFixRunner.java     — Startup schema patch-ups for H2/Postgres
│       ├── security/
│       │   ├── JwtService.java          — Sign/parse JWTs
│       │   ├── JwtAuthFilter.java       — Populates the security context from Bearer tokens
│       │   └── AuthenticatedUser.java   — Authenticated principal (id/email/role)
│       ├── controller/
│       │   ├── AuthController.java      — register/login/me
│       │   ├── BankController.java      — Bank CRUD + legal-opinion format
│       │   ├── ReferenceController.java — Legal reference documents
│       │   └── VerificationController.java — Submission flow, opinion PDF, history
│       ├── dto/                         — Request/Response DTOs
│       ├── model/
│       │   ├── User.java
│       │   ├── Bank.java
│       │   ├── LegalReference.java
│       │   └── VerificationJob.java
│       ├── repository/                  — JPA repositories
│       └── service/
│           ├── BankService.java
│           ├── VerificationService.java     — Extraction, chunked analysis, OCR fallback
│           ├── GroqClient.java              — Groq API integration (text + vision)
│           ├── TextExtractionService.java   — Shared PDFBox/Tika text extraction
│           ├── LegalOpinionPdfService.java  — Renders the final opinion PDF
│           └── AuthService.java
├── frontend/
│   ├── package.json
│   ├── vite.config.ts
│   ├── index.html
│   └── src/
│       ├── main.tsx
│       ├── App.tsx                      — Routing, navbar, auth-aware nav
│       ├── index.css                    — Design system (editorial/paper theme)
│       ├── context/AuthContext.tsx       — Auth state, token persistence
│       ├── components/ProtectedRoute.tsx
│       ├── api/client.ts                — API client (auth-aware fetch wrapper)
│       └── pages/
│           ├── LoginPage.tsx / RegisterPage.tsx
│           ├── UserPage.tsx             — Submit for Legal Opinion flow
│           └── AdminPage.tsx            — Bank & legal-opinion format management
└── README.md
```

---

## Environment Variables

| Variable             | Required | Default                        | Description                              |
|-----------------------|----------|----------------------------------|-------------------------------------------|
| `GROQ_API_KEY`        | ✅ Yes   | —                                | Groq API key (only LLM credential needed) |
| `GROQ_MODEL`          | No       | `openai/gpt-oss-120b`            | Open-weight model for legal reasoning     |
| `GROQ_VISION_MODEL`   | No       | `qwen/qwen3.6-27b`               | Open-weight vision model for OCR. Model availability and rate limits vary by Groq account — verify against your own key if you change this. |
| `JWT_SECRET`          | ✅ Yes (outside dev) | insecure dev default | Signing secret for auth tokens |
| `ADMIN_EMAIL`         | For admin access | —                     | Seeds the one ADMIN account on first boot |
| `ADMIN_PASSWORD`      | For admin access | —                     | Password for the seeded ADMIN account     |
| `DB_HOST`             | prod profile only | —                   | Postgres host (Neon or any Postgres)      |
| `DB_PORT`             | No       | `5432`                           | Postgres port                             |
| `DB_NAME`             | prod profile only | —                   | Postgres database name                    |
| `DATABASE_USERNAME`   | prod profile only | `sa` (H2 default)   | Database username                         |
| `DATABASE_PASSWORD`   | prod profile only | *(empty)*           | Database password                         |

---

## Free Deployment: Render (hosting) + Neon (database)

This is the free deployment path this project is set up for: **Render** hosts the backend and
frontend, **Neon** hosts the Postgres database. They're split like this on purpose — Render's free
Postgres expires 30 days after creation (then a 14-day grace period, then deletion); Neon's free
tier has no such expiry, it just auto-suspends the compute when idle and wakes on the next query.
Render's free web service also wipes its local disk on every restart/spin-down, so the database
must live somewhere else anyway for data to actually persist.

A `render.yaml` Blueprint at the repo root defines both Render services, so setup is one import
instead of configuring each service by hand.

> **Free-tier heap note**: the Dockerfile runs with `-Xmx384m` to fit Render's free 512MB-RAM
> container. This is enough for typical legal documents (tested up to several hundred KB real
> files). If you later move to a paid Render plan for more headroom on very large files, raise
> `-Xmx` in `backend/Dockerfile` accordingly.

### Step 1: Create the free Neon database

1. Go to [neon.tech](https://neon.tech) and sign up (no card required) → create a project.
2. On the project dashboard, open **Connection Details** and copy the individual parameters
   (not the combined URL) — you need: **Host**, **Database name**, **Role/User**, **Password**.
   Port is always `5432`.

### Step 2: Deploy via Render Blueprint

1. Go to [dashboard.render.com](https://dashboard.render.com) and sign up/log in.
2. Click **New +** → **Blueprint**.
3. Connect your GitHub account and select this repo — Render will detect `render.yaml`.
4. Render shows both services (`instalego-backend`, `instalego-frontend`) it's about to create.
   Before clicking **Apply**, you'll be prompted for the env vars marked `sync: false`:

   | Key | Value |
   |-----|-------|
   | `GROQ_API_KEY` | your Groq key |
   | `JWT_SECRET` | a long random string |
   | `ADMIN_EMAIL` / `ADMIN_PASSWORD` | your admin credentials |
   | `DB_HOST` | Host from Neon's Connection Details |
   | `DB_NAME` | Database name from Neon |
   | `DATABASE_USERNAME` | Role/User from Neon |
   | `DATABASE_PASSWORD` | Password from Neon |

5. Click **Apply**. Render builds both services (~5-8 mins for the backend Docker build).
6. Once live, open the frontend service's URL — that's your shareable link.

> If Render assigns the backend a different URL than `instalego-backend.onrender.com` (only
> happens if that name is already taken), update the `VITE_API_URL` env var on the
> `instalego-frontend` service to match, then trigger a manual redeploy of the frontend.

### What to expect on the free tier

- **Cold starts**: the backend spins down after 15 min of inactivity; the next request takes
  30-60s to wake it back up. The frontend (static site) has no such delay.
- **Uploads directory is ephemeral**: raw uploaded files are wiped on every backend restart. This
  doesn't affect completed opinions — the report and PDF are generated from data stored in Neon,
  not from the original file — but a session left mid-upload across a restart would need to start
  over.
- **Neon auto-suspends** after ~5 min of inactivity by default; the next query wakes it
  automatically (a few hundred ms of extra latency on that first query, not a full outage).

---

## Tech Stack

| Category       | Technology                              |
|----------------|-------------------------------------------|
| Backend        | Java 17, Spring Boot 3.2.5, Spring Security |
| Frontend       | React 18, Vite 5, TypeScript 5.5       |
| Database       | H2 (dev), PostgreSQL (prod)            |
| PDF            | Apache PDFBox 3.0.1                    |
| Text Extract   | Apache Tika 2.9.2 (fallback)           |
| LLM            | Groq — `openai/gpt-oss-120b` (text), `meta-llama/llama-4-scout` (vision) |
| Auth           | Spring Security + JJWT (HS256), BCrypt  |
| Build          | Maven, npm                             |

---

## Notes

- **Context-window safety**: large document sets are automatically analyzed in batches (map-reduce)
  instead of one giant prompt, so a 30MB file can never overflow the model's context window.
- **LLM Timeouts**: Groq calls have a 60-second timeout with retry + exponential backoff for rate
  limits (429s).
- **File Storage**: Uploads go to `./uploads/` — structure supports swapping to S3 later.
- **Ownership**: verification sessions belong to whoever started them; ADMINs can view any session.
- **Template Versioning**: not applicable to the current opinion-format flow — each bank has one
  active legal-opinion format at a time, replaceable by deleting and re-uploading.
