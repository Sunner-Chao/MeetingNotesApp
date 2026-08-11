# 智悟本 iOS

这是智悟本的原生 SwiftUI 客户端。首个可运行版本复用现有服务端接口：账户登录、STT 最终转写和小Woo会议纪要生成均使用服务器签发的短令牌。

## 构建

在 macOS 安装完整 Xcode 后：

```bash
xcodebuild -project ios/ZhiWuBen.xcodeproj -target ZhiWuBen -sdk iphonesimulator -destination 'platform=iOS Simulator,name=ZhiWuBen-iPhone' build
```

首次运行在“账户服务地址”输入运行时地址，例如 `https://your-server.example/api`。STT 服务地址需要在“服务设置”中单独配置。

## 真机 IPA

真机构建不在源码中保存 Apple 账号或证书信息。先在 Xcode 的 Settings > Accounts 登录 Apple Developer 账号，并连接一次目标 iPhone，使 Xcode 可以注册设备和创建开发描述文件。然后通过环境变量传入签名配置：

```bash
export APPLE_DEVELOPMENT_TEAM="你的 Team ID"
export IOS_BUNDLE_ID="已注册的 Bundle ID"
export IOS_EXPORT_METHOD="development"
export IOS_DEFAULT_ACCOUNT_ENDPOINT="账户服务地址"
export IOS_DEFAULT_STT_ENDPOINT="STT 服务地址"
ios/scripts/build-ipa.sh
```

脚本默认将 `ZhiWuBen-iOS-v<版本>.ipa` 输出到仓库根目录。服务地址只作为构建环境默认值注入，用户仍可在 App 设置中覆盖。`development` 产物仅能安装到描述文件中已注册的设备；Ad Hoc、TestFlight 或 App Store 分发需要对应的付费开发者账号和导出方式。可配置项示例见 `ios/config/signing.env.example`，所有值也都可以在 CI 中由密钥管理系统注入。

`Info.plist` 暂时允许开发环境使用 HTTP 服务，以兼容现有服务器部署；发布 App Store 前必须切换 HTTPS，并将 `NSAllowsArbitraryLoads` 收紧为按域名的 ATS 例外。
