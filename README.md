<div align="center">
  <h2>VideoMind-AI</h2>
  <p>
    <a href="https://github.com/aliaaa-hu/VideoMind-AI/stargazers"><img src="https://img.shields.io/github/stars/aliaaa-hu/VideoMind-AI?style=flat-square" alt="GitHub Stars"></a>
    <img src="https://img.shields.io/badge/Java-21-E76F00?style=flat-square" alt="Java 21">
    <img src="https://img.shields.io/badge/Spring%20Boot-3.5.9-6DB33F?style=flat-square" alt="Spring Boot 3.5.9">
    <img src="https://img.shields.io/badge/Vue-3-42B883?style=flat-square" alt="Vue 3">
    <img src="https://img.shields.io/badge/MySQL-8-4479A1?style=flat-square" alt="MySQL 8">
    <img src="https://img.shields.io/badge/Redis-7-DC382D?style=flat-square" alt="Redis 7">
    <img src="https://img.shields.io/badge/RocketMQ-5.3.4-D77310?style=flat-square" alt="RocketMQ 5.3.4">
    <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="MIT License"></a>
  </p>
</div>

<div align="center">
  An evidence-first <strong>Video Agent</strong> for long-form content understanding.
  <br />
  Turn long videos into structured, searchable knowledge with timestamped evidence and grounded follow-up questions.
</div>

## Core Capabilities

### Reliable video-task pipeline

- **Chunked upload and resume support** — The client uploads 5 MB chunks, Redis tracks completed chunks, and MinIO stores merged video objects so interrupted uploads can continue from the last completed chunk.
- **Asynchronous processing** — RocketMQ moves analysis off request threads and returns a task ID immediately. Redisson locks by content fingerprint and analysis goal to prevent duplicate work.
- **Cost guardrails** — Per-user and global token buckets rate-limit AI calls. ASR and model calls use bounded exponential-backoff retries for transient failures.

### Temporal multimodal VideoContext

- **Dual extraction paths** — FFmpeg slices audio into 60-second chunks while scene-change detection extracts key frames, with a 30-second fallback sample for static slides and whiteboards.
- **Parallel and fault-tolerant processing** — ASR and OCR run in independent bounded thread pools. Perceptual hashing removes near-duplicate frames, and one path can still contribute when the other fails.
- **Unified structure** — Speech spans, OCR text, key frames, and timestamps become `VideoSegment` records, so retrieval and verification are model-independent.

```text
[02:00 - 03:00]
ASR      Next, we will cover preorder traversal of a binary tree.
OCR      Preorder: root node, left subtree, right subtree.
Evidence frame_000125.jpg
```

### Evidence-constrained AgentLoop

- **Planner, Executor, and Critic roles** — The Planner breaks a goal into executable tasks, the Executor creates structured conclusions and evidence, and the Critic validates coverage and timestamp support.
- **Closed-loop verification** — When the Critic finds missing content, the Agent requests targeted additional evidence before producing the final result.
- **Automatic mode routing** — Goals route to general, learning, review, or creation modes, with a safe fallback to general mode.
- **Bounded cost** — AgentLoop executes at most two rounds to permit focused revision while limiting latency and token cost.

### Long-video retrieval and recovery

- **Hybrid retrieval** — Every five minutes, the system creates segment summaries, keywords, and embeddings. Keyword matching and Qdrant semantic recall select top-k source evidence.
- **Graceful degradation** — When Qdrant or embeddings are unavailable, the service falls back to local keyword matching and existing-vector ranking.
- **Checkpoint recovery** — MySQL stores durable recovery state while Redis serves as hot cache for `VideoContext`, chunks, plans, Critic state, and results.
- **Observable state** — The frontend receives task stages through SSE. Failed messages are persisted and can be republished through an administrative endpoint.

## Technology Stack

| Layer | Technology | Purpose |
| :--- | :--- | :--- |
| Web | Vue 3, Vite, SSE, Marked | Uploads, Agent workspace, live progress, and safe Markdown rendering |
| API | Java 21, Spring Boot 3.5.9, Undertow, MyBatis-Plus | Authentication, media management, orchestration, and REST APIs |
| Async and cache | RocketMQ 5.3.4, Redis 7.4, Redisson | Background processing, cached state, rate limits, locks, and idempotency |
| Data and storage | MySQL 8, MinIO, Qdrant | Business data, video objects, checkpoints, and vector retrieval |
| Video and AI | FFmpeg, Tesseract, LangChain4j, DeepSeek, TeleSpeechASR, BGE-M3 | Media processing, multimodal extraction, reasoning, and embeddings |
| Deployment | Docker Compose | Local infrastructure orchestration |

## Run Locally

### Prerequisites

| Component | Requirement | Notes |
| :--- | :--- | :--- |
| JDK | 21 | Backend runtime |
| Node.js | 22 | Vue and Vite runtime |
| Docker | Compose v2 | Starts MySQL, Redis, MinIO, Qdrant, and RocketMQ |
| FFmpeg | Available on `PATH` | Audio segmentation and key-frame extraction |
| Tesseract | `eng` recommended; `chi_sim` optional | Key-frame OCR |
| yt-dlp | Optional | Needed only for online video URLs |

### 1. Create local configuration

```bash
cp .env.example .env
```

Replace the example passwords for MySQL, Redis, MinIO, and Qdrant. Set `SILICONFLOW_API_KEY` to enable transcription, embeddings, and AI analysis. Keep secrets in local `.env`; never commit it.

### 2. Start infrastructure

```bash
./scripts/dev-up.sh
```

The script validates the local environment and waits for MySQL, Redis, MinIO, Qdrant, and RocketMQ. Infrastructure listens only on `127.0.0.1` by default.

### 3. Start the backend

```bash
set -a
source .env
set +a

cd server
./mvnw spring-boot:run
```

Verify the backend:

```bash
curl http://localhost:9090/health
```

The expected response is `{"code":0,"message":"success","data":"UP"}`.

### 4. Start the frontend

```bash
set -a
source .env
set +a

cd client
npm ci
npm run dev
```

Open `http://localhost:5173`. During development, Vite proxies requests to the backend. To view the built-in UI demo without the backend, open `http://localhost:5173/?demo`.

### Troubleshooting

| Issue | Resolution |
| :--- | :--- |
| Backend cannot connect to MySQL or Redis | Run `docker compose --env-file .env ps`, confirm services are healthy, and verify passwords in `.env`. |
| Browser cannot reach the backend | Visit `/health`, then check `VITE_DEV_PROXY_TARGET` or `VITE_API_BASE_URL`. |
| Video processing reports a missing command | Confirm `ffmpeg` and `tesseract` are available on `PATH`; configure `FFMPEG_DIR` or `OCR_COMMAND` if needed. |
| AI request returns 401 | Check `SILICONFLOW_API_KEY` and restart the backend. |

Stop local infrastructure:

```bash
docker compose --env-file .env down
```

## Directory Structure

```text
VideoMind-AI
├── client/              # Vue 3 workspace
├── server/              # Spring Boot API and Video Agent
├── rocketmq/            # Broker configuration
├── docker-compose.yml   # Infrastructure orchestration
└── .env.example         # Local configuration template
```

## License

Released under the [MIT License](LICENSE).
