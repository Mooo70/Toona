"""解析单个 YouTube 视频，选出用户指定画质与编码的视频轨和音频轨。

本模块完全运行在 Android App 内。yt-dlp 负责解析 YouTube 的签名与选轨，
Kotlin 下载选出的两条 DASH 轨并用 Android 原生 MediaMuxer 无损合并成 MP4。

当前为「免登录最小闭环」：不携带 Cookie，公开视频可下；命中机器人校验或
需要登录的内容会返回明确错误。
"""

from __future__ import annotations

import json
import re
import traceback
from typing import Any


URL_RE = re.compile(r"https?://[^\s]+", re.IGNORECASE)
FORMAT_EXPR = "bv+ba[acodec^=mp4a]/bv+ba/bv*+ba/b"
QUALITY_OPTIONS = {"best", "1080", "720", "480"}
CODEC_OPTIONS = {"auto", "h264", "h265", "av1"}
QUALITY_LABELS = {"best": "最高", "1080": "1080p", "720": "720p", "480": "480p"}
CODEC_LABELS = {"auto": "自动", "h264": "H.264", "h265": "H.265", "av1": "AV1"}


def _first_url(text: str) -> str:
    match = URL_RE.search(text or "")
    return match.group(0).rstrip("，。！？、,.;!?)）]}") if match else ""


def _headers(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        return {}
    return {str(key): str(item) for key, item in value.items() if item is not None}


def _codec_family(codec: Any) -> str:
    normalized = str(codec or "").lower()
    if normalized.startswith(("avc", "h264")):
        return "h264"
    if normalized.startswith(("hev", "hvc", "h265")):
        return "h265"
    if normalized.startswith(("av01", "av1")):
        return "av1"
    if normalized.startswith(("vp09", "vp9")):
        return "vp9"
    return normalized or "unknown"


def _track_payload(item: dict[str, Any]) -> dict[str, Any]:
    url = item.get("url") or ""
    return {
        "url": url,
        "urls": [url] if url else [],
        "format_id": item.get("format_id") or "",
        "ext": item.get("ext") or "",
        "width": item.get("width") or 0,
        "height": item.get("height") or 0,
        "fps": item.get("fps") or 0,
        "vcodec": item.get("vcodec") or "none",
        "acodec": item.get("acodec") or "none",
        "tbr": item.get("tbr") or 0,
        "filesize": item.get("filesize") or item.get("filesize_approx") or 0,
        "headers": _headers(item.get("http_headers")),
    }


def resolve(text: str, quality: str = "best", codec: str = "h264") -> str:
    url = _first_url(text)
    if not url:
        return json.dumps(
            {"ok": False, "stage": "input", "error": "没有识别到 YouTube 链接"},
            ensure_ascii=False,
        )
    lowered = url.lower()
    if "youtube.com" not in lowered and "youtu.be" not in lowered:
        return json.dumps(
            {"ok": False, "stage": "input", "error": "这不是 YouTube 链接"},
            ensure_ascii=False,
        )

    quality = str(quality or "best").lower()
    codec = str(codec or "h264").lower()
    if quality not in QUALITY_OPTIONS:
        return json.dumps(
            {"ok": False, "stage": "input", "error": f"不支持的画质选项：{quality}"},
            ensure_ascii=False,
        )
    if codec not in CODEC_OPTIONS:
        return json.dumps(
            {"ok": False, "stage": "input", "error": f"不支持的编码选项：{codec}"},
            ensure_ascii=False,
        )

    try:
        import yt_dlp

        format_sort = []
        if quality != "best":
            format_sort.append(f"res:{quality}")
        if codec != "auto":
            format_sort.append(f"vcodec:{codec}")

        options = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "noplaylist": True,
            "socket_timeout": 25,
            "format": FORMAT_EXPR,
            "merge_output_format": "mp4",
        }
        if format_sort:
            options["format_sort"] = format_sort

        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=False)

        requested = info.get("requested_formats") or []
        video = next(
            (item for item in requested if (item.get("vcodec") or "none") != "none"),
            None,
        )
        audio = next(
            (item for item in requested if (item.get("acodec") or "none") != "none"),
            None,
        )
        if not video or not audio:
            raise RuntimeError("YouTube 未返回可合并的视频轨和音频轨")
        if not video.get("url") or not audio.get("url"):
            raise RuntimeError("YouTube 轨道缺少下载地址，可能需要登录或命中机器人校验")

        actual_height = int(video.get("height") or 0)
        actual_codec = _codec_family(video.get("vcodec"))
        quality_met = quality == "best" or actual_height == int(quality)
        codec_met = codec == "auto" or actual_codec == codec
        requested_label = f"{QUALITY_LABELS[quality]} / {CODEC_LABELS[codec]}"
        actual_quality_label = f"{actual_height}p" if actual_height else "未知画质"
        actual_codec_label = CODEC_LABELS.get(actual_codec, actual_codec.upper())
        actual_label = f"{actual_quality_label} / {actual_codec_label}"
        selection_warning = ""
        if not quality_met or not codec_met:
            selection_warning = f"目标档位不可用，已自动选择 {actual_label}"

        payload = {
            "ok": True,
            "stage": "resolved",
            "site": "youtube",
            "source_url": url,
            "webpage_url": info.get("webpage_url") or url,
            "id": info.get("id") or "youtube",
            "title": info.get("title") or "YouTube视频",
            "uploader": info.get("uploader") or info.get("channel") or "",
            "duration": info.get("duration") or 0,
            "requested_quality": quality,
            "requested_codec": codec,
            "actual_height": actual_height,
            "actual_codec": actual_codec,
            "quality_met": quality_met,
            "codec_met": codec_met,
            "selection_summary": f"请求 {requested_label}；实际 {actual_label}",
            "selection_warning": selection_warning,
            "video": _track_payload(video),
            "audio": _track_payload(audio),
        }
        return json.dumps(payload, ensure_ascii=False)
    except Exception as exc:  # pragma: no cover - requires live platform access
        return json.dumps(
            {
                "ok": False,
                "stage": "resolve",
                "site": "youtube",
                "url": url,
                "error": f"{type(exc).__name__}: {exc}",
                "trace_tail": traceback.format_exc().splitlines()[-5:],
            },
            ensure_ascii=False,
        )
