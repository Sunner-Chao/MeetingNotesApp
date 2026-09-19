# 自建会议语音服务

此目录提供单节点 LiveKit 1.13.7 部署基线。Android 使用 LiveKit 2.20.2，通过已有账号后端入会。支持语音通话及可选的服务端共享转写/纪要；视频和共享屏幕不开放。默认静音，点击麦克风后才上传语音。共享转写另由主持人开启，默认不启用，不归档房间原始音频。

## 部署顺序

1. 在具备公网可达网络的 Linux 主机准备 Docker，或在 Windows/Linux 运行同版本官方二进制。下载后对照官方 release checksums 校验。生产密钥在私有环境生成：API key 与至少 32 字符随机 secret。
2. 将 `livekit.yaml` 复制到私有部署目录。LiveKit 通过 `LIVEKIT_KEYS` 环境变量获取 `key: secret` 格式的映射，后端使用 `backend.env.example` 中同一组值。不要把实际凭据加入版本库、日志或 Obsidian。Docker Compose 方案使用 Linux host 网络，不适用于直接套用 Docker Desktop 网络。
3. 为媒体信令分配域名及可信 TLS，在反向代理转发 WSS/HTTP 到 7880，支持 WebSocket Upgrade；连接超时应允许长通话。7880 管理 RPC 只允许账号后端访问，公网反向代理应阻止 `/twirp/`。后端 `MEETINGNOTES_RTC_API_URL` 指向内部地址，客户端只能获得 WSS 地址和短时入会票据。
4. 开放 7882/UDP 与 7881/TCP 的媒体端口，配置正确的公网地址/NAT。蜂窝和企业网应补齐独立 TURN 域名、可信证书与 TURN/TLS 443（独立 IP 或负载均衡分流），以及所需 UDP 端口；普通 HTTP 反向代理无法代理全部 WebRTC 媒体。
5. **保持 `room.auto_create: false`。** 房间只能由账号后端主动创建。否则已结束房间可能被尚未过期的旧票据重新创建。Android `media_ready` 目前表示后端配置齐全，真实服务可达性在入会时验证。
6. 先启动 LiveKit，再为账号后端加载 RTC 环境变量并重启。空房间 300 秒自动回收，最后一人离开后 30 秒回收，再次入会由后端重建；业务房间数据仍保留。
7. 使用两个普通账号验收双向音频、默认静音、后台通知、蓝牙/手机路由、蜂窝与 WiFi、主持人结束、掉线重连，再发布 Android 正式签名 OTA。

`compose.yaml` 默认从相邻配置启动；用 `LIVEKIT_CONFIG_PATH` 指定私有生产配置。不携带主机地址和生产密钥。

## 连接与退出

- `POST /api/account/rooms/{id}/media-session`：沿用账号 Bearer 会话，核对房间成员与状态；返回 60 秒票据，仅可发布麦克风音轨。客户端不持有 LiveKit 管理密钥。
- Android 的“离开通话”关闭 SDK 连接，保留房间成员身份。房间“离开/结束”、账号删除会排队调用媒体清理接口，失败后后台每 5 秒重试。账号切换和服务地址切换立即退出本机通话。
- 静音是停止发布语音，不能作为录音同意。共享转写只在当前房间成员全部同意且主持人显式开始后订阅音轨；撤回同意或新成员未同意时暂停转写。
- 本机录音与通话共享麦克风占用锁。当前有未完成录音（包括已暂停）时不能加入通话；不会悄悄丢弃录音。结束/暂存该录音后再入会。
- 票据短时有效不等于逐票据可撤销。离开房间后旧票据在剩余有效期内可能仍能重新连接尚开放的媒体房间；Android 会核对成员身份并退出。跨设备恶意票据重用的即时撤销需后续服务端会话校验/令牌撤销设计，不能当成已实现。

## 无设备验证

在隔离 Python 环境安装 `livekit==1.1.19`、`numpy` 和后端依赖，运行：

```text
python server/scripts/verify_room_audio.py --server-binary <已校验的 livekit-server 可执行文件>
python -m unittest discover -s server/tests -p test_room_media.py -v
```

脚本仅监听本机回环地址，创建随机临时密钥与数据库，两个客户端通过 WebRTC/Opus 交换合成音频，检查非零音频帧、静音/恢复、主持人结束后的断开与旧票据拒绝。完成后关闭子进程并清理临时文件。不使用手机、模拟器、麦克风或真实录音；不能替代真实公网与 Android 硬件验收。

## 可选共享转写与纪要

1. 为账号后端安装 `server/backend-service/requirements-rooms.txt`（Python 3.11 基线），部署时同时包含 `audio_ingress.py`、`room_workspace.py`、`room_transcription_worker.py` 与 `server/knowledge/templates/meeting-minutes/listening-room-v1/`。数据库表由后端启动自动新增，升级前按既有方式备份账户库。
2. 配置 `MEETINGNOTES_ROOM_STT_URL` 为 V100 流式 WebSocket 地址。公网必须 WSS；仅回环地址允许 WS。复用已有 `ACCOUNT_TOKEN_SECRET`，两端须一致，每条轨道签发所属成员的短期 STT 令牌；不要求用户配置额外令牌。静态服务令牌仅作为独立测试部署选项，不具备成员用量归属。
3. 确认已有 Agent 网关及普通账号纪要额度可用，选择已启用的 `MEETINGNOTES_ROOM_REPORT_PROVIDER`。费用归属发起生成的主持人，按请求 ID 幂等结算。纯房间转写当前不新增腾讯云计费路径；本地 STT 用量计费能力沿用 V100 自身实现，不等于新增了房间分钟扣费。
4. 最后设置 `MEETINGNOTES_ROOM_TRANSCRIPTION_ENABLED=1` 并重启。缺依赖/缺配置不会宣称可用。服务健康租约仅表明转写工作进程在运行，真正媒体/STT 可达性在订阅音轨时确认。
5. 每个发布音轨占用一条 V100 流式连接，静音但未取消发布时连接仍可能占用。V100 当前默认 `V100_MAX_STREAMS=2`，与个人转写共享。不能把房间允许人数当成模型并发能力；先实测并发、GPU 显存与延迟再配置生产容量。满载会明确暂停并保留已有文字，本阶段不自动切云端。
6. Android 房间内提供“转录与共享纪要”：按时间段与成员显示名查看；主持人暂停并完成尾句刷新后生成共享纪要。实时内容直接复用，不重传整场录音。界面关闭后服务端继续，重新打开按修订游标同步。

暂停包含最长约 5 秒的 STT 尾句等待，超过时显示失败并保留已经识别的内容。房间结束先给尾句有限刷新窗口再清理媒体。时间为服务端接收音轨的近似相对时间，不是字级强制对齐；暂停控制与已在途音频可能有短暂交叠。撤回同意后立即拒绝新文字写入，音轨订阅在下一次检查时关闭。

扩展验证（真实 WebRTC 音频 + 明确标注的 STT/纪要协议测试替身，不代表模型准确率）：

```text
python server/scripts/verify_room_workspace.py --server-binary <已校验的 livekit-server 可执行文件>
python -m unittest discover -s server/tests -p test_room_workspace.py -v
```

生产发布前仍需完成真实 V100 与 Agent 连通性、两账号公网/WiFi/蜂窝、TURN 和人工设备验收。本阶段代码不自动修改已有生产部署或 OTA。
