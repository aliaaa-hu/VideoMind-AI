# VideoMind-AI Web

The Vue 3 frontend for VideoMind-AI, including video upload, Agent analysis, evidence review, and follow-up questions.

```bash
npm ci
npm run dev
```

The development server proxies requests to `http://localhost:9090` by default. If the backend is hosted elsewhere, set `VITE_DEV_PROXY_TARGET` in the root `.env` file. For separate frontend and backend deployments, set `VITE_API_BASE_URL`.
