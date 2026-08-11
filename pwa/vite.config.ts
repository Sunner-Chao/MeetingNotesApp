import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "PWA_");
  const backendTarget = env.PWA_DEV_BACKEND_URL || "http://127.0.0.1:8090";
  const requestedPort = Number(env.PWA_DEV_PORT || "4173");

  return {
    base: "/app/",
    plugins: [
      react(),
      VitePWA({
        registerType: "autoUpdate",
        includeAssets: ["icons/apple-touch-icon.png"],
        manifest: {
          name: "智悟本轻享版",
          short_name: "智悟本",
          description: "录音转写、会议整理与小Woo智能纪要",
          lang: "zh-CN",
          theme_color: "#0f7a50",
          background_color: "#f5f7f6",
          display: "standalone",
          orientation: "portrait-primary",
          scope: "/app/",
          start_url: "/app/",
          icons: [
            { src: "icons/icon-192.png", sizes: "192x192", type: "image/png" },
            { src: "icons/icon-512.png", sizes: "512x512", type: "image/png" },
            { src: "icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" }
          ]
        },
        workbox: {
          navigateFallback: "/app/index.html",
          navigateFallbackDenylist: [/^\/api\//],
          runtimeCaching: []
        }
      })
    ],
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
