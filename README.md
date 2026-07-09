# InstaLego 🧱

> **Legal Document → Bank-Specific Format Converter**
>
> Upload legal documents (PDF, image, DOCX), select a target bank, and get a filled structured output PDF — powered by Google Gemini 2.5 Flash.

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
                    │  Google Gemini│     │  Local Disk   │
                    │  2.5 Flash   │     │  /uploads     │
                    └──────────────┘     └──────────────┘
```

- **Backend**: Java 17 + Spring Boot 3.x (REST API)
- **Frontend**: React 18 + Vite + TypeScript
- **Database**: H2 (local dev) / PostgreSQL (production)
- **LLM**: Google Gemini 2.5 Flash (multimodal — understands PDFs/images natively)
- **PDF**: Apache PDFBox 3.x for generation
- **No auth** for v1 — usable locally

---

## Prerequisites

| Tool       | Version   | Install                                  |
|------------|-----------|------------------------------------------|
| Java       | 17+       | [Adoptium](https://adoptium.net/)        |
| Node.js    | 18+       | [nodejs.org](https://nodejs.org/)        |
| Maven      | 3.9+      | Included via Maven wrapper (see below)   |
| Git        | any       | [git-scm.com](https://git-scm.com/)      |

---

## Getting a Gemini API Key (Free)

1. Go to [Google AI Studio](https://aistudio.google.com/app/apikey)
2. Click **"Get API Key"** → **"Create API Key"**
3. Copy the key — it starts with `AIza...`
4. The free tier allows ~15 requests/minute, which is plenty for development

---

## Local Setup

### 1. Clone & Navigate

```bash
git clone <your-repo-url>
cd InstaLego
```

### 2. Set the Gemini API Key

**Windows (Command Prompt)**:
```cmd
set GEMINI_API_KEY=AIza...
```

**Windows (PowerShell)**:
```powershell
$env:GEMINI_API_KEY="AIza..."
```

**macOS / Linux**:
```bash
export GEMINI_API_KEY=AIza...
```

> 💡 Add this to your shell profile (`~/.bashrc`, `~/.zshrc`) to persist it.

### 3. Run the Backend

The backend uses H2 (embedded file-based database) by default — no PostgreSQL setup needed for local development.

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

**For production (PostgreSQL)**:
```bash
export DATABASE_URL=jdbc:postgresql://...
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

### Admin Panel (`/admin`)

1. **Add a Bank**: Enter a bank name (e.g., "HDFC Bank", "ICICI Bank") and click "Add Bank"
2. **Upload a Template**: Select the bank, upload a **sample PDF** that represents the desired output format
3. **Review Derived Schema**: Gemini automatically analyzes the template and suggests fields. You can:
   - Edit field names and descriptions
   - Change data types (text, date, number, boolean)
   - Toggle required flag
   - Add or remove fields
4. **Save**: Click "Save Schema" to persist the template

### User Page (`/`)

1. **Select a Bank**: Only banks with active templates are shown
2. **Upload a Document**: PDF, JPG, PNG, or DOCX (max 15MB)
3. **Submit**: Click "Convert Document"
4. **Wait**: The job processes asynchronously — the page polls for status
5. **Download**: When complete, click "Download Output PDF"
6. **On failure**: The error message is displayed clearly

---

## API Endpoints

| Method | Endpoint                            | Description                          |
|--------|-------------------------------------|--------------------------------------|
| POST   | `/api/banks`                        | Create a new bank                    |
| GET    | `/api/banks`                        | List all banks                       |
| GET    | `/api/banks/{id}`                   | Get a bank by ID                     |
| GET    | `/api/banks/with-template`          | List banks with active templates     |
| POST   | `/api/banks/{bankId}/template`      | Upload template PDF + derive schema  |
| PUT    | `/api/banks/{bankId}/template`      | Save confirmed field schema          |
| GET    | `/api/banks/{bankId}/template`      | Get active template for a bank       |
| GET    | `/api/banks/{bankId}/template/versions` | Get all template versions       |
| POST   | `/api/jobs`                         | Create a conversion job              |
| GET    | `/api/jobs/{id}`                    | Poll job status                      |
| GET    | `/api/jobs/{id}/output`             | Download output PDF                  |

---

## Project Structure

```
InstaLego/
├── backend/
│   ├── Dockerfile                 — Container image for Render/Docker
│   ├── pom.xml
│   └── src/main/java/com/instalego/
│       ├── InstaLegoApplication.java
│       ├── config/
│       │   ├── AsyncConfig.java         — Async executor for job processing
│       │   └── WebConfig.java           — CORS + static resources
│       ├── controller/
│       │   ├── BankController.java      — Bank CRUD
│       │   ├── TemplateController.java  — Template upload & schema
│       │   └── JobController.java       — Job creation & polling
│       ├── dto/                         — Request/Response DTOs
│       ├── model/
│       │   ├── Bank.java
│       │   ├── BankTemplate.java
│       │   └── ConversionJob.java
│       ├── repository/                  — JPA repositories
│       └── service/
│           ├── BankService.java
│           ├── TemplateService.java
│           ├── GeminiService.java       — Gemini API integration
│           ├── ConversionService.java   — Async job processing
│           └── PdfGenerationService.java — PDFBox output generation
├── frontend/
│   ├── package.json
│   ├── vite.config.ts
│   ├── index.html
│   └── src/
│       ├── main.tsx
│       ├── App.tsx                      — Routing + layout
│       ├── index.css                    — Global styles
│       ├── api/client.ts                — API client
│       └── pages/
│           ├── UserPage.tsx             — Document upload & status
│           └── AdminPage.tsx            — Bank & template management
└── README.md
```

---

## Environment Variables

| Variable             | Required | Default                | Description                              |
|----------------------|----------|------------------------|------------------------------------------|
| `GEMINI_API_KEY`     | ✅ Yes   | —                      | Google Gemini API key                    |
| `DATABASE_URL`       | No       | `jdbc:h2:file:./data/instalego` | PostgreSQL JDBC URL (prod)    |
| `DATABASE_USERNAME`  | No       | `sa`                   | Database username                        |
| `DATABASE_PASSWORD`  | No       | *(empty)*              | Database password                        |

---

## Deployment on Render

> **Note**: Render's Web Service does **not** have a native "Java" runtime option. Use the **Docker** runtime instead (a `Dockerfile` is already included in `backend/`).

### Step 1: Create a PostgreSQL Database

1. Go to [dashboard.render.com](https://dashboard.render.com)
2. Click **New +** → **PostgreSQL**
3. Name: `instalego-db`, Database: `instalego`, User: `instalego_user`
4. Click **Create Database**
5. After provisioning, copy the **Internal Database URL**, **Username**, and **Password**

### Step 2: Deploy the Backend (Docker)

1. Click **New +** → **Web Service**
2. Connect your GitHub repo (`vetukurishivavarma-eng/InstaLego`)
3. Fill in:
   - **Name**: `instalego-backend`
   - **Runtime**: Select **Docker** (not Java)
   - **Branch**: `master`
   - **Dockerfile Path**: `/backend/Dockerfile`
   - **Plan**: Free

4. Click **Advanced** and add these **Environment Variables**:

   | Key | Value |
   |-----|-------|
   | `GEMINI_API_KEY` | `AIza...` (your Gemini key) |
   | `DATABASE_URL` | The Internal Database URL from Step 1 |
   | `DATABASE_USERNAME` | `instalego_user` |
   | `DATABASE_PASSWORD` | The password from Step 1 |

5. Click **Create Web Service**
6. Wait for build & deploy (~5-8 mins)
7. Copy your backend URL: `https://instalego-backend.onrender.com`

### Step 3: Deploy the Frontend (Static Site)

1. Click **New +** → **Static Site**
2. Connect the same GitHub repo
3. Fill in:
   - **Name**: `instalego-frontend`
   - **Branch**: `master`
   - **Root Directory**: `frontend`
   - **Build Command**: `npm install && npm run build`
   - **Publish Directory**: `frontend/dist`

4. Add a **Redirect/Rewrite Rule**:
   - **Source**: `/*`
   - **Destination**: `/index.html`
   - **Action**: **Rewrite** (enables client-side routing)

5. Click **Create Static Site**
6. Your frontend URL: `https://instalego-frontend.onrender.com`

### Summary

| Service | Type | URL |
|---------|------|-----|
| PostgreSQL | Database | Internal to Render |
| Backend | Docker | `https://instalego-backend.onrender.com` |
| Frontend | Static Site | `https://instalego-frontend.onrender.com` |

> ⚠️ Render's free tier spins down after 15 min of inactivity. First request after idle takes 30-60s to wake up.

---

## Tech Stack

| Category       | Technology                              |
|----------------|-----------------------------------------|
| Backend        | Java 17, Spring Boot 3.2.5             |
| Frontend       | React 18, Vite 5, TypeScript 5.5       |
| Database       | H2 (dev), PostgreSQL (prod)            |
| PDF            | Apache PDFBox 3.0.1                    |
| Text Extract   | Apache Tika 2.9.2 (fallback)           |
| LLM           | Google Gemini 2.5 Flash                |
| Build          | Maven, npm                             |

---

## Notes

- **LLM Timeouts**: Gemini calls have a 60-second timeout with retry + exponential backoff for rate limits (429s)
- **File Storage**: Uploads go to `./uploads/` — structure supports swapping to S3 later
- **No Authentication**: v1 is intentionally auth-free for local use
- **Template Versioning**: Each template upload creates a new version; old jobs reference their original version
