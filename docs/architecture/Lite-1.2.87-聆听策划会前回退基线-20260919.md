# Lite 1.2.87：聆听·策划会前回退基线

## 基线

- Git commit：`2cda38c8`
- Git tag：`lite-baseline-1.2.87-20260919`
- 当前分支：`codex/light-enjoy`
- Lite 版本：`1.2.87` / `versionCode 10287`
- applicationId：`com.oa.automation.light`
- APK SHA-256：`e93068fd5911e9c170c55e14f296b1fbed567b3440b18d53c66e16ccdd358efb`

## 线上回退入口

- OTA 元数据：<https://lstwin.space/api/app-update/android/light>
- APK 下载：<https://lstwin.space/api/app-update/android/light/apk/10287>

该版本保留现有 `自定义会议`，不包含新增的 `聆听·策划会`。后续实现应在此检查点之后增加独立会议类型，不改变 `自定义会议` 的模板编排和历史兼容键。

## 说明

检查点只纳入已跟踪源码和测试。工作区中的 `.codex*` 截图、临时 APK、探测响应和日志等生成物未纳入版本，也不作为回退依赖。
