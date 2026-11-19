# 家庭 HTTP 代理试用版

首次打开时确认预填的代理信息、输入代理密码，再按原 Jellyfin 页面登录。之后可在 App 的「设置 → 代理」修改地址、端口、用户名和密码。编辑时密码留空会保留原值。App 使用 Android Keystore 加密保存整份配置，并在以后启动时自动使用。升级旧试用版时，已保存的密码会与原默认地址、端口和用户名一起继续使用。

在支持应用快捷方式的桌面上，也可以长按 Jellyfin 图标选择「代理」。连接失败时，提示提供重试和取消。修改代理请手动进入设置。

保存对现有代理配置的修改时，需输入预设确认密码 `jellyfin`。这个密码用于防误操作，和代理服务器的登录密码分开。

在项目根目录构建独立的 Debug APK：

```powershell
.\gradlew.bat :app:testLibreDebugUnitTest :app:assembleLibreDebug -PhomeProxyTrial=true
```

试用版通过 App 内的回环 HTTP 代理接入 WebView、原生播放器、SDK API、WebSocket、图片和下载。连接限定到家庭 Jellyfin 的预设地址，普通构建保持上游连接方式。电视投屏接收器和外部播放器有各自的网络连接，体验验证以 App 内播放为准。

试用时可用同一视频观察首次打开、连续播放、往前和往后拖动、退出再续播，以及 Wi‑Fi 和移动流量切换后的恢复。本版保留 Jellyfin 的播放选择。此前电脑外网测试中普通 HTTP 200 和 HLS 超过 40 Mbps，Range 约 4.8 Mbps。播放器实际发出 Range 时，仍可能出现较慢的加载或拖动恢复。

当前公网入口为明文 HTTP，代理认证和 Jellyfin 请求会经过该连接。试用范围限定为家庭设备。
