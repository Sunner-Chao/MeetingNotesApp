---
title: 蜂窝 IPv4 WebSocket 升级故障与修复
tags:
  - meeting-notes-app
  - stt
  - websocket
  - operations
date: 2026-08-18
---

# 蜂窝 IPv4 WebSocket 升级故障与修复

## 现象

手机使用蜂窝网络点击“即刻倾听”时，`https://lstwin.space/admin/` 的事件列表没有新增记录；IPv6 网络测试则能正常出现实时会话事件。

## 根因

蜂窝 IPv4 流量经过云服务器 Nginx，再通过 WireGuard 到达 Windows Caddy。Nginx 虽然使用了 `proxy_http_version 1.1`，但没有转发 WebSocket 的 `Upgrade` 和 `Connection` 头。请求因此以普通 HTTP 到达 Caddy，`/ws/transcribe-stream` 返回 404，STT 服务根本没有创建会话，所以管理台没有 `session_open`。

实际链路：

```text
蜂窝 IPv4
  -> 118.25.43.185 Nginx
  -> WireGuard 10.77.0.1/10.77.0.2
  -> Windows Caddy :443
  -> STT :8888
```

## 修复

文件：`edge-router/nginx-lstwin-frontdoor.conf`

- 在 `http` 级别增加 `$http_upgrade` 到 `$connection_upgrade` 的映射。
- `lstwin.cloud` 与 `lstwin.space` 两个 HTTPS 反代位置都转发：
  - `Upgrade: $http_upgrade`
  - `Connection: $connection_upgrade`
- 上传到云服务器 `/etc/nginx/sites-enabled/lstwin-frontdoor.conf`，通过 `nginx -t` 后 reload。
- 备份移到 `/var/backups/nginx/`，不放在 `sites-enabled`，避免被通配 include 误加载。

## 验证

- 云服务器自身强制 IPv4 访问 `/health`：HTTP 200。
- 云服务器自身强制 IPv4 发起 WebSocket 握手：HTTP 101；无令牌时由 STT 正确返回 Unauthorized，证明已经到达 WebSocket 路由。
- 使用受保护 STT 令牌握手：HTTP 101；Windows STT 日志出现 `WebSocket accepted` 和后台转写任务创建。
- Nginx 当前配置语法通过，服务状态为 `active`。

## 后续验收

从真实蜂窝网络重新点击“即刻倾听”，应能在管理台看到 `session_open`、音频缓冲和实时 partial 事件。若手机仍失败，应继续检查 Android 端 DNS/代理和蜂窝运营商链路，而不是重复修改 STT 模型或令牌。

## 双栈保留与 Android 路由（2026-08-18）

`lstwin.space` 不停用 AAAA：

- A 记录继续指向云服务器 `118.25.43.185`，作为 IPv4 中继入口。
- AAAA 记录继续指向 Windows 公网 IPv6，供普通 PC 和 IPv6 网络直连。
- DDNS-GO 已恢复维护 `lstwin.space` 的 IPv6；不要为修复蜂窝网络而删除主域 AAAA。

Android 的 STT 网络客户端（实时 WebSocket、文件转写、连接检查、音频归档）统一使用 `Ipv4RelayDns`：当域名存在 A 记录时只返回 IPv4 地址，避免 IPv4 握手失败后 OkHttp 自动退回 IPv6、造成“应用看似连接但云端中继和管理台没有事件”；只有纯 IPv6 域名才保留 IPv6 解析。

## 移动数据固定 IPv4 中继（1.2.24）

真实蜂窝回归仍没有在云端产生 `/ws/transcribe-stream`，说明运营商 DNS/IPv6 路径可能让应用绕过云端 A 记录。1.2.24 增加构建时配置 `MEETINGNOTES_STT_IPV4_RELAY_ADDRESS`，Release 当前注入云服务器 IPv4 `118.25.43.185`。Android URL 仍为 `https://lstwin.space`，因此 TLS SNI、Host 和证书校验不变；`Ipv4RelayDns` 只对该 STT endpoint 主机返回固定 IPv4，不改写其他服务或 Debug 的 `10.0.2.2`。

这不是删除 AAAA 的替代 DNS 记录：PC/IPv6 网络仍可使用 `lstwin.space` AAAA 直连，移动端 Release 仅将 STT 连接明确送到云端 IPv4 入口。最终验收标准为云端 Nginx 看到移动 IP 的 `/ws/transcribe-stream` 返回 101、Windows STT 日志来源为 `10.77.0.1`、管理台出现 `session_open`，并在 Android 看到实时 partial 文本。

云服务器本机以正确 SNI 访问 `https://lstwin.space/health` 返回 HTTP 200，Nginx 443 监听正常，配置测试通过。当前 AVD 与 Windows 主机位于同一家庭网络，访问自身公网 IPv4 存在回环限制，IPv4 TLS 会被重置；因此 AVD 只能验证客户端严格走 IPv4，不能替代真实蜂窝网络的最终验收。真实蜂窝回归应以云端 Nginx `/ws/transcribe-stream` 101、Windows STT 来源 `10.77.0.1` 和管理台 `session_open` 为准。

## Reset 二次诊断与稳态修复（2026-08-18）

升级头修复后，蜂窝会话已经能建立，但本地链路仍可能在传输阶段 reset：Android 日志连续出现 `Connection reset`，Windows STT 管理台对应会话已 `session_open/session_start`，仅收到约 5 秒 PCM（`159744` bytes）后缓冲量不再增长，约 40 秒后断开。这不是模型首帧延迟，而是蜂窝 IPv4 -> Nginx -> WireGuard -> Caddy 的长连接数据面中断。

同一轮 Android 日志显示本地重试耗尽后切换到腾讯云地址，并收到 `WebSocket connected`。云端 Nginx 也记录了 `/stt-cloud/ws/transcribe-stream` 的 `101`，云端服务创建了蜂窝来源的流式会话并收到音频帧。因此“自动切云”确实发生，且至少完成 WebSocket 握手；但握手成功不代表整段音频已经持续送达或已有有效 partial 文本，切换期间的缓存音频可能丢失。

进一步读取云端管理事件确认：蜂窝来源的云端会话出现 `session_start`，`stream_provider=tencent-realtime-standard`，随后连续产生 `partial` 文本，最后正常 `session_disconnect`。所以本次“云端仍未连接”的判断不成立；云端兜底是成功的，失败点在本地链路的持续传输。

本次调整：

- `edge-router/nginx-lstwin-frontdoor.conf` 的两个域名反代位置增加 `proxy_buffering off`、`proxy_request_buffering off`、`proxy_socket_keepalive on`，并将长连接读写超时统一为 3600 秒；已上传云端，`nginx -t` 通过并 reload，服务状态为 `active`。
- Android `StreamingSttClient` 的 OkHttp ping 从 30 秒降为 15 秒，缩短半开连接发现时间。
- 跨服务切换现在等待目标 WebSocket 的真实 `onOpen` 确认后才返回成功；超时或失败会清理残留重连并显示“本地与云端实时识别均不可用”，不再把“已发起切换”误报成“云端已启用”。

后续移动数据验收需同时满足：本地路径管理台音频持续增长且出现 partial；若本地 reset，Android 显示正在连接云端，随后目标云端出现 `session_start`、音频增长和 partial，而不是只看 `101` 握手状态。

## OTA 修复版（1.2.25）

2026-08-18 已构建并发布 Android `1.2.25`（versionCode `10225`）：

- Release 使用固定证书，SHA-256 为 `512a5cfb22ebd5330af75abeb5000b17025b12462e5207e6f9864f31fe78b77a`，APK 非 debuggable。
- 正式包继续使用 `https://lstwin.space` 作为 TLS/Host，同时由 `Ipv4RelayDns` 固定解析到云服务器 `118.25.43.185`；不会删除主域 AAAA。
- OkHttp WebSocket ping 调整为 15 秒；本地失败切换腾讯云时必须等到服务端返回有效 `session_id`，超时会清理残留连接。
- 云端 OTA 清单已验证，最新 APK SHA-256 为 `ca565beb2d82fe4b0b39f2563203240865cdf19936ac5b56b963cb545aa4ac25`；公网元数据返回 `10225`，`10224` 仍可下载，服务器仅保留这两个版本。

因此，本次已完成“代码修复 + 固定签名构建 + OTA 发布 + 公网下载校验”。真实蜂窝网络仍建议在手机上点击一次“即刻倾听”，以观察本地路径是否稳定；若再次 reset，应确认 UI 等待云端有效会话后再显示兜底成功。
