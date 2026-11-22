# Toona

Android 端纯本地视频与图文下载技术验证项目。第一台目标设备：小米 15，Android 16，arm64-v8a。

## 当前里程碑

- [x] 独立于全局 Skill 的 Android 项目
- [x] 系统分享文本入口
- [x] 五平台链接识别
- [x] Chaquopy Python 3.12 接入
- [x] yt-dlp 打包配置
- [x] 小米 15 / Android 16 真机构建、安装与 Python 启动验证
- [x] 抖音匿名解析、清晰度档位选择与前台下载服务
- [x] 抖音图文识别、缩略图预览、全选/逐张选择与批量保存
- [x] 抖音分享短链、`/note/<id>`、`/video/<id>` 及电脑网页 `modal_id/aweme_id` 网址识别
- [x] 抖音图文优先使用公开详情接口的无平台水印图片源，并逐张验收格式与尺寸
- [x] 下载进度、取消、MediaStore 相册保存与音视频轨道验收
- [x] B站画质（最高/1080p/720p/480p）与编码（H.264/H.265/AV1/自动）选择
- [x] B站 AAC 双轨下载、备用 CDN 与 Android 原生无损合并
- [x] 小红书分享文案、`xhslink.com/.cn` 短链及作品网址识别
- [x] 小红书公开图文原图预览、选择与批量保存
- [x] 小红书公开视频优先原始文件源，缺失时回退公开页最佳流
- [ ] YouTube 本地登录态与 JS 运行时
- [ ] Instagram 本地登录态与代理

## 隔离原则

`D:\Skills\video-download-pipeline` 是只读上游参考。此项目不从原 Skill 目录运行代码，也不复制 Cookie、浏览器配置、下载历史、数据库或日志。

## 构建

```powershell
./gradlew.bat :app:assembleDebug
```

APK 预期位置：`app/build/outputs/apk/debug/app-debug.apk`。当前测试包为
`dist/Toona-0.6.0-spike-debug.apk`（仅包含 `arm64-v8a`，面向小米 15 侧载测试）。

## 抖音图文边界

Toona 只读取当前公开页面本来就能访问的媒体地址，不绕过账号、私密、好友可见、已删除等访问控制。所谓“无水印”是指选择 `aweme_detail.images[].url_list` 的图片源，避开带有 `tplv-dy-water` 转换的下载地址；作者已经写进原图的文字、贴纸或标识会原样保留。

## 小红书边界

小红书仅解析分享链接对应的公开作品页，不访问搜索、评论、账号主页或登录后接口，也不保存账号 Cookie。完整作品网址通常需要保留分享时携带的 `xsec_token`；失效链接会提示重新复制。图文使用作品页的原图文件标识；视频优先 `originVideoKey`，若作者作品没有提供原始视频源，则回退到公开页最佳视频流，并在解析日志中明确提示。

