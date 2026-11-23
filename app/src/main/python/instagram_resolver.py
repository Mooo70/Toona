"""解析 Instagram 单条视频（Reel/视频帖）或图文轮播（carousel）为可下载源。

Instagram 即使走通代理，对未登录请求也会返回 ``geoblock_required``（内容不可见）。
所以调用方必须传入长期有效的 ``sessionid`` cookie。本模块把该值写成一个临时的
Netscape cookie 文件交给 yt-dlp，由 yt-dlp 负责 shortcode -> media_id 换算以及
签名媒体接口。

Instagram 视频是「音视频分离」的双轨（DASH），所以视频会返回 ``video`` 与
``audio`` 两条轨道，交给 Kotlin 侧用 MediaMuxer 在本地无损合并；图文轮播则返回
``images`` 列表。
"""

from __future__ import annotations

import json
import os
import re
import tempfile
import traceback
from typing import Any

import yt_dlp

UA = (
    "Mozilla/5.0 (Linux; Android 16; 25129PN74C) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/140.0.0.0 Mobile Safari/537.36"
)
REFERER = "https://www.instagram.com/"

URL_RE = re.compile(
    r"https?://(?:www\.)?instagram\.com/(?:reel|reels|p|tv)/[A-Za-z0-9_-]+/?",
    re.IGNORECASE,
)
FALLBACK_URL_RE = re.compile(r"https?://[^\s]+", re.IGNORECASE)
SHORTCODE_RE = re.compile(r"/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)", re.IGNORECASE)
TRAILING = "，。、；：！？》）】”’\",.;:!?)>]}'"


def _first_url(text: str) -> str:
    match = URL_RE.search(text or "")
    if not match:
        match = FALLBACK_URL_RE.search(text or "")
        if not match or "instagram.com" not in match.group(0).lower():
            return ""
        return match.group(0).rstrip(TRAILING)
    return match.group(0).rstrip(TRAILING)


def _shortcode(url: str) -> str:
    match = SHORTCODE_RE.search(url or "")
    return match.group(1) if match else ""


def _is_http(value: Any) -> bool:
    return isinstance(value, str) and value.startswith(("https://", "http://"))


def _is_video(entry: dict[str, Any]) -> bool:
    vcodec = str(entry.get("vcodec") or "").lower()
    if vcodec and vcodec != "none":
        return True
    for fmt in entry.get("formats") or []:
        if not isinstance(fmt, dict):
            continue
        codec = str(fmt.get("vcodec") or "").lower()
        if codec and codec != "none":
            return True
    return False


def _image_candidates(entry: dict[str, Any]) -> list[dict[str, Any]]:
    """Return every usable still-image candidate, best resolution first.

    yt-dlp exposes Instagram photos through ``thumbnails`` rather than ``url``.
    A single-photo post has no video format at all, while image entries inside a
    carousel have the same shape.  Reading all candidates also avoids saving the
    small preview which yt-dlp places in the convenience ``thumbnail`` field.
    """
    candidates: list[dict[str, Any]] = []
    seen: set[str] = set()

    for item in entry.get("thumbnails") or []:
        if not isinstance(item, dict):
            continue
        url = item.get("url")
        if not _is_http(url) or url in seen:
            continue
        seen.add(url)
        candidates.append(
            {
                "url": url,
                "width": int(item.get("width") or 0),
                "height": int(item.get("height") or 0),
            }
        )

    for key in ("url", "thumbnail"):
        url = entry.get(key)
        if not _is_http(url) or url in seen:
            continue
        seen.add(url)
        candidates.append(
            {
                "url": url,
                "width": int(entry.get("width") or 0),
                "height": int(entry.get("height") or 0),
            }
        )

    return sorted(
        candidates,
        key=lambda item: (item["width"] * item["height"], item["width"], item["height"]),
        reverse=True,
    )


def _image_item(entry: dict[str, Any], index: int) -> dict[str, Any] | None:
    candidates = _image_candidates(entry)
    if not candidates:
        return None
    best = candidates[0]
    return {
        "index": index,
        "urls": [item["url"] for item in candidates],
        "width": best["width"],
        "height": best["height"],
    }


def _images(entries: list[Any]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            continue
        if _is_video(entry):
            continue  # 混合轮播里的视频项暂不当作图片处理
        item = _image_item(entry, index)
        if item is not None:
            result.append(item)
    return result


def _track(item: dict[str, Any], sessionid: str) -> dict[str, Any]:
    url = item.get("url") or ""
    headers: dict[str, str] = {}
    http_headers = item.get("http_headers")
    if isinstance(http_headers, dict):
        for key, value in http_headers.items():
            if value is not None:
                headers[str(key)] = str(value)
    headers["User-Agent"] = headers.get("User-Agent", UA)
    headers["Referer"] = headers.get("Referer", REFERER)
    headers["Cookie"] = f"sessionid={sessionid}"
    headers["Accept"] = "*/*"
    return {
        "url": url,
        "urls": [url] if url else [],
        "ext": item.get("ext") or "",
        "width": int(item.get("width") or 0),
        "height": int(item.get("height") or 0),
        "vcodec": item.get("vcodec") or "none",
        "acodec": item.get("acodec") or "none",
        "filesize": int(item.get("filesize") or item.get("filesize_approx") or 0),
        "headers": headers,
    }


def _best_progressive(formats: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Instagram 的 video_versions 是「已合并好的单文件 mp4」（编码未标注）。

    特征：ext=mp4、vcodec/acodec 均为空或 none。安卓侧直接下载即可，无需二次合并。
    """
    candidates = [
        f for f in formats
        if _is_http(f.get("url"))
        and str(f.get("ext") or "").lower() == "mp4"
        and str(f.get("vcodec") or "").lower() in ("", "none")
        and str(f.get("acodec") or "").lower() in ("", "none")
    ]
    if not candidates:
        return None
    return max(
        candidates,
        key=lambda f: (
            int(f.get("height") or 0),
            int(f.get("filesize") or f.get("filesize_approx") or 0),
        ),
    )


def _progressive_payload(
    entry: dict[str, Any], common: dict[str, Any], sessionid: str,
) -> dict[str, Any]:
    formats = [f for f in (entry.get("formats") or []) if isinstance(f, dict)]
    progressive = _best_progressive(formats)
    if progressive is None:
        raise RuntimeError("该视频只有分离轨（VP9/AV1），没有可直接下载的单文件版本，暂不支持")
    track = _track(progressive, sessionid)
    return {
        **common,
        "kind": "video",
        "urls": track["urls"],
        "headers": track["headers"],
        "ext": track["ext"],
        "width": track["width"],
        "height": track["height"],
        "size": track["filesize"],
    }


def _extract(url: str, sessionid: str) -> dict[str, Any]:
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".txt", prefix="ig_cookies_", delete=False
    )
    try:
        handle.write("# Netscape HTTP Cookie File\n")
        handle.write(".instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\t%s\n" % sessionid)
        handle.close()
        options = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            # Instagram 的纯图片帖子没有任何视频 format。保留元数据，交给
            # _image_candidates 从 thumbnails 中选择最高分辨率图片。
            "ignore_no_formats_error": True,
            "socket_timeout": 25,
            "cookiefile": handle.name,
            # 让 yt-dlp 把全部轨道带回来，具体选 H.264 还是在 Python 里挑，避免编码标签差异。
            "format": "bv*+ba/b",
        }
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=False)
        return info
    finally:
        try:
            handle.close()
        except OSError:
            pass
        try:
            os.unlink(handle.name)
        except OSError:
            pass


def _build(info: dict[str, Any], url: str, sessionid: str) -> dict[str, Any]:
    canonical_url = str(info.get("webpage_url") or url)
    shortcode = _shortcode(canonical_url) or str(info.get("id") or _shortcode(url))
    common = {
        "ok": True,
        "stage": "resolved",
        "platform": "instagram",
        "source_url": canonical_url,
        "shortcode": shortcode,
        "media_id": str(info.get("id") or ""),
        "desc": str(info.get("description") or info.get("title") or ""),
        "nickname": str(
            info.get("channel") or info.get("uploader") or info.get("uploader_id") or ""
        ),
        "headers": {
            "User-Agent": UA,
            "Referer": REFERER,
            "Cookie": f"sessionid={sessionid}",
            "Accept": "*/*",
        },
    }

    entries = info.get("entries")
    if info.get("_type") == "playlist" or entries:
        images = _images(entries or [])
        if images:
            return {
                **common,
                "kind": "image",
                "images": images,
                "image_count": len(images),
            }
        first_video = next(
            (e for e in (entries or []) if isinstance(e, dict) and _is_video(e)),
            None,
        )
        if first_video is not None:
            return _progressive_payload(first_video, common, sessionid)
        raise RuntimeError("该轮播帖没有可下载的图片或视频")

    if _is_video(info):
        return _progressive_payload(info, common, sessionid)

    image = _image_item(info, 0)
    if image is not None:
        return {
            **common,
            "kind": "image",
            "images": [image],
            "image_count": 1,
        }
    raise RuntimeError("没有识别到可下载的视频或图片源")


def resolve(share_text: str, sessionid: str = "") -> str:
    try:
        if not (sessionid or "").strip():
            return json.dumps(
                {
                    "ok": False,
                    "stage": "instagram_resolve",
                    "error": "缺少 Instagram 登录态（sessionid）；请先在 App 里粘贴 sessionid",
                },
                ensure_ascii=False,
            )
        url = _first_url(share_text)
        if not url:
            return json.dumps(
                {
                    "ok": False,
                    "stage": "instagram_resolve",
                    "error": "没有识别到 Instagram 的 reel/p 链接",
                },
                ensure_ascii=False,
            )
        info = _extract(url, sessionid.strip())
        payload = _build(info, url, sessionid.strip())
        return json.dumps(payload, ensure_ascii=False)
    except Exception as error:
        message = f"{type(error).__name__}: {error}"
        lowered = message.lower()
        if any(
            key in lowered
            for key in ("login", "sessionid", "available to everyone", "geoblock", "empty media")
        ):
            message += "；请确认 sessionid 已登录且未过期，并已开启能访问 Instagram 的代理/VPN"
        return json.dumps(
            {
                "ok": False,
                "stage": "instagram_resolve",
                "error": message,
                "trace_tail": traceback.format_exc().splitlines()[-6:],
            },
            ensure_ascii=False,
        )
