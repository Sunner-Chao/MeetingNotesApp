# 智悟本用户端 Web

智悟本用户端 Web 是面向手机、平板和桌面浏览器的标准 HTTPS Web 应用，提供录音、实时转录预览、音频或文本导入、最终转写、会议纪要、本机历史记录、Word/PDF/音频分享和研学社区浏览。社区页的“创建内容”工作台可从已有会议载入纪要或转写，保存私有草稿、编辑元数据并提交人工审核；提交后状态在“我的发布”中持续可见。它与 Android 共享账户、会议和服务端 API，逐步按 Android 功能矩阵补齐能力。

## 本地开发

要求 Node.js 20 或更高版本。开发服务地址和端口通过环境变量配置：

```powershell
Copy-Item .env.example .env.local
npm install
npm run dev
```

默认访问 `http://127.0.0.1:4173/app/`。`WEB_DEV_BACKEND_URL` 指向本地 Backend，Vite 会将 `/api` 请求代理到该地址；旧的 `PWA_DEV_BACKEND_URL` 和 `PWA_DEV_PORT` 仍兼容。页面“我的 > 服务设置”也可在运行时覆盖账户服务地址。

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

## 浏览器限制

生产环境必须使用 HTTPS。录音依赖网页保持前台；手机系统锁屏或长时间切到后台时，浏览器可能暂停录音，因此页面会显示完整性提醒。实时预览通过 WSS 携带短期账户令牌连接，连接失败不影响本地分片和结束后的完整文件转写。用户端 Web 不依赖 Service Worker、离线缓存或安装到主屏幕能力。

## 社区内容工作台

在社区页右上角点击“创建内容”，或切换到“我的”后点击“从会议创建”。选择一条本机会议即可载入纪要/转写，补充目的地、北京时间行程日期、标签和参观点，完成隐私与发布权利确认后可保存草稿或提交审核。草稿使用 `PUT /api/account/community/drafts/{post_id}` 原地更新；一旦提交审核，服务端会锁定编辑，避免产生重复帖子或覆盖审核中的内容。现场图片可在工作台逐张选择，保存草稿时会通过 manifest 与分片接口上传原图和缩略图，并展示进度与失败状态。

## 验证命令

```powershell
npm run typecheck
npm test
npm run build
```
