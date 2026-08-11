# 智悟本轻享版 PWA

智悟本轻享版是面向 iPhone、iPad 和桌面浏览器的前台会议工作台，提供录音、音频或文本导入、最终转写、小Woo 纪要生成、本机历史记录及 Word/PDF/音频分享。

## 本地开发

要求 Node.js 20 或更高版本。开发服务地址和端口通过环境变量配置：

```powershell
Copy-Item .env.example .env.local
npm install
npm run dev
```

默认访问 `http://127.0.0.1:4173/app/`。`PWA_DEV_BACKEND_URL` 指向本地 Backend，Vite 会将 `/api` 请求代理到该地址；`PWA_DEV_PORT` 可覆盖开发端口。页面“我的 > 服务设置”也可在运行时覆盖账户服务地址。

## 生产构建

```powershell
npm ci
npm run typecheck
npm test
npm run build
npm run visual:check
```

视觉检查默认连接 `http://127.0.0.1:4173/app/` 并使用本机 Chrome。可通过 `PWA_VISUAL_BASE_URL`、`PWA_VISUAL_BROWSER_CHANNEL` 和 `PWA_VISUAL_OUTPUT_DIR` 覆盖；运行前需先启动开发或预览服务。

产物生成在 `pwa/dist`。Backend 默认从该目录提供 `/app/`，也可用服务端环境变量 `PWA_DIST_DIR` 指向其他构建目录。账号、Agent 和转写请求均经同源 `/api` 转发，浏览器不会取得服务端 STT 管理令牌。

## iPhone 安装

生产环境必须使用 HTTPS。用 Safari 打开 `/app/`，点击分享并选择“添加到主屏幕”。录音依赖网页保持前台；iOS 锁屏或长时间切到后台时，浏览器可能暂停录音，因此页面会显示完整性提醒。

## 验证命令

```powershell
npm run typecheck
npm test
npm run build
```
