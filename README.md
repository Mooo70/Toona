# Toona

<p align="center">
  <img src="artwork/ic_launcher_master.png" width="128" alt="Toona App 图标">
</p>

Toona 是一款在 Android 手机上本地运行的视频与图集下载工具。粘贴分享文案或作品网址后，应用会识别平台、解析媒体、预览图集，并把选中的内容保存到系统相册。

## 功能

- 抖音：视频、图文作品，支持分享文案、短链和网页链接
- 小红书：视频、普通图集和实况图集，支持短链与完整作品网址
- B站：视频下载，可选清晰度和编码，音视频在手机本地合并
- YouTube：公开视频下载，可选清晰度和编码；部分内容可能触发平台机器人校验
- Instagram：视频和图集；登录态保存在手机本地，解析时需要可访问 Instagram 的网络环境
- 通用体验：系统分享入口、一键清空、图集预览与选择、下载进度、取消任务、结果验收

## 技术栈

- Kotlin、Jetpack Compose、Material 3
- Chaquopy 与 Python 3.12
- yt-dlp、httpx、gmssl
- Android MediaStore、MediaMuxer、前台下载服务

## 本地构建

环境要求：Android Studio、JDK 17、Android SDK 36、Python 3.12。

```powershell
./gradlew.bat :app:assembleDebug
```

调试 APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前仅打包 `arm64-v8a`，最低支持 Android 10（API 29）。

## 隐私与安全

- 媒体解析与下载在手机本地完成，不设自建后端。
- 仓库不包含用户 Cookie、浏览器配置、下载历史、数据库或日志。
- Instagram `sessionid` 由用户在 App 内输入，仅保存在本机应用数据中；不会写入仓库。
- 应用不会绕过账号私密、好友可见、已删除等访问控制。

## 使用边界

请只下载你有权访问和保存的内容，并遵守平台服务条款、著作权规则及所在地法律。项目仅用于个人学习和技术研究，不保证第三方平台接口长期可用。

## 已知限制

- YouTube 的部分请求会被平台机器人校验拦截。
- Instagram 通常需要有效登录态和可访问其服务的网络环境。
- 第三方平台页面和接口变化可能导致解析暂时失效。

## 开源许可

本项目采用 [MIT License](LICENSE)。第三方组件与来源说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

