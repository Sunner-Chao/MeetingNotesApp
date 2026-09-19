# 房间共享转录与软硬件边界验证

日期：2026-09-19。范围：当前 `codex/light-enjoy` 软件实现与未来耳机接入契约。无生产部署，无 OTA；未使用手机、模拟器、实际麦克风或真实用户录音。

## Android

命令：`android/gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon`。

- 419 项 JVM 测试通过，0 失败、0 错误、0 跳过；新增 7 项共享工作区测试。
- Debug 构建及固定证书检查通过，构建只用于开发验证，不作为产品发布。
- Lint：0 errors、80 warnings；沿用现有工具链和既有告警，没有进行工具链升级。没有设备截图，因此未宣称实际小屏视觉验收完成。
- 新增回归覆盖增量文本合并、成员快照变化重建、账号切换清空、迟到房间响应隔离、临时网络故障保留文本、丧失成员权限清空、重复生成拦截、生成新稿不保留旧正文。

## 后端

分别运行 `python -m unittest discover -s server/tests -p <文件> -q`：

| 测试文件 | 数量 | 结果 |
| --- | ---: | --- |
| `test_room_workspace.py` | 20 | 通过 |
| `test_room_media.py` | 13 | 通过 |
| `test_account_routes.py` | 14 | 通过 |
| `test_v100_protocol.py` | 9 | 通过 |

合计 56 项。覆盖主持人/成员隔离、录音意愿、增量游标、尾句/续录、过期工作进程、账号删除、报告不可变快照/幂等/旧稿清理、PCM 校验/时间映射与既有账户 STT 令牌。

V100 协议测试使用替身模型，无 CUDA 和真实准确率测量。测试环境已有 Starlette/httpx 迁移警告及 NumPy 重载警告，未影响通过结果。

## 实际音频传输链路

运行：`python server/scripts/verify_room_workspace.py --server-binary <已校验的 livekit-server 可执行文件>`。

真实部分：本机 LiveKit 1.13.7、两个独立 Python RTC 客户端、WebRTC/Opus 音频传输、16 kHz PCM 接收、WebSocket、服务端房间状态与 SQLite。仅回环网络、随机临时凭据、临时数据库，结束后关闭进程并清理测试资源。

替身部分：STT 按收到的真实音频字节生成固定测试文本；纪要回调确定性输出快照。**不代表生产 V100 识别率、真实 Agent 质量、蜂窝可达性或硬件效果。**

最终一次通过结果：

```json
{
  "passed": true,
  "transport": "LiveKit/WebRTC/Opus loopback",
  "received_frames": [863, 861],
  "startup_and_bidirectional_audio_seconds": 2.766,
  "workspace_tracks_including_resume": 4,
  "workspace_audio_bytes": 83840,
  "preview_before_pause": true,
  "pause_flushes_final": true,
  "resume_preserves_rows_and_clock": true,
  "consent_revocation_stops_writes": true,
  "members_read_same_report": true,
  "report_requests": 1,
  "mute_and_unmute": true,
  "host_end_disconnects_both": true,
  "ended_room_rejects_old_ticket": true,
  "microphone_or_device_used": false
}
```

2.766 秒包含启动媒体服务、建房和两端入会，不能当成音频延迟或 STT 首字延迟。

验证曾暴露暂停时在途帧时间戳越过 cutoff 导致尾句丢弃，已将该尾句估计时间收束到暂停边界，重跑传输测试通过。Android 测试替身最初没有在 POST 后反映队列状态，现已修正替身并全量重跑通过。

## 仍需验收

实际 V100/Agent 房间联调、超过两轨的容量与延迟、房间 STT 和个人 STT 竞争、公网 TURN/WiFi/蜂窝、真实手机回声与蓝牙路由、未来耳机采集/控制/私密输出。不能由本轮通过结果推导这些能力已上线。

设计依据：[[../architecture/聆听策划会-软件主链路与耳机扩展契约-20260919]]。
