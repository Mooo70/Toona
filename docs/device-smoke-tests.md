# 真机冒烟测试

## 2026-11-22 · Toona 0.5.0-spike

- 设备：Xiaomi 15（24129PN74C），Android 16，arm64-v8a
- 场景：抖音公开图文作品 `7285559250619813155`
- 解析：识别 48 张图片，默认全选，可取消全选并逐张勾选
- 地址检查：仅返回 `aweme_detail.images[].url_list`，48 张候选地址均不含 `tplv-dy-water`
- 下载：单选第 1 张后成功写入 `Pictures/Toona`
- 验收：WebP，1440 × 1920，190,320 bytes
- 目视检查：未发现抖音平台水印；作者原图内文字保留
- 视频回归：短链 `AkNyK2eXOVs` 仍解析为视频，返回 1920 × 1080、14 个档位
- APK：`dist/Toona-0.5.0-spike-debug.apk`

## 2026-11-22 · Toona 0.5.1-spike

- 修复电脑网页版作品地址无法识别的问题
- 覆盖 `/jingxuan?modal_id=<id>`、`/user/...&modal_id=<id>`、`aweme_id=<id>` 及 URL 编码参数
- 真机输入 `https://www.douyin.com/jingxuan?modal_id=7285559250619813155`
- 结果：成功识别同一图文作品的 48 张图片并显示选择界面
- 构建与 Android lint：PASS
- APK：`dist/Toona-0.5.1-spike-debug.apk`

## 2026-11-22 · Toona 0.6.0-spike

- 新增小红书/RedNote 平台识别：分享文案、`xhslink.com/.cn`、`/explore/`、`/discovery/item/`、用户作品网址
- 图文：公开页结构化数据解析、缩略图预览、选择与批量保存到 `Pictures/Toona`
- 视频：原始文件源优先，公开页最佳流回退，保存到 `Movies/Toona` 后验收音视频轨道
- 安全边界：仅请求分享链接和已验证的小红书 CDN 域名，不使用 Cookie，不访问登录态接口
- 解析器合成样例：图文及视频两种结构 PASS
- 构建与 Android lint：PASS
- 小米 15 分享入口：作品网址识别为“小红书”，解析按钮与 0.6.0 日志正常显示
- 小米 15 无效令牌测试：Python 模块正常返回“分享链接已失效或触发访问限制”，应用未崩溃
- 真机公开作品下载：等待使用测试者刚复制的有效分享链接验收
- APK：`dist/Toona-0.6.0-spike-debug.apk`
