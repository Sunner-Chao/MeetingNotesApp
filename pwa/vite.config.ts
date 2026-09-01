import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  // This is a conventional Web build. PWA_* remains as a compatibility prefix
  // for existing local environments while new setups can use WEB_*.
  const env = { ...loadEnv(mode, process.cwd(), "PWA_"), ...loadEnv(mode, process.cwd(), "WEB_") };
  const backendTarget = env.WEB_DEV_BACKEND_URL || env.PWA_DEV_BACKEND_URL || "http://127.0.0.1:8090";
  const requestedPort = Number(env.WEB_DEV_PORT || env.PWA_DEV_PORT || "4173");

  return {
    base: "/app/",
    plugins: [react()],
    server: {
      host: true,
      port: Number.isFinite(requestedPort) ? requestedPort : 4173,
      strictPort: false,
      proxy: {
        "/api": {
          target: backendTarget,
          changeOrigin: true,
          secure: false
        }
      }
    },
    preview: {
      host: true,
      port: Number.isFinite(requestedPort) ? requestedPort : 4173,
      strictPort: false
    }
  };
});
