"""Android-only technical probe.

This module is intentionally independent from D:\\Skills\\video-download-pipeline.
It proves that Chaquopy and yt-dlp can load on ARM64 Android before any of the
Windows skill code is ported.
"""

from __future__ import annotations

import json
import platform
import re
import sys
import traceback
from typing import Any


URL_RE = re.compile(r"https?://[^\s]+", re.IGNORECASE)


def _first_url(text: str) -> str:
    match = URL_RE.search(text or "")
    return match.group(0).rstrip("，。！？、,.;!?)）]}") if match else ""


def detect_site(text: str) -> str:
    url = _first_url(text).lower()
    if "douyin.com" in url:
        return "douyin"
    if "bilibili.com" in url or "b23.tv" in url:
        return "bilibili"
    if "youtube.com" in url or "youtu.be" in url:
        return "youtube"
    if "instagram.com" in url:
        return "instagram"
    return "unknown"


def environment() -> str:
    payload: dict[str, Any] = {
        "ok": True,
        "python": sys.version.split()[0],
        "machine": platform.machine(),
        "platform": platform.platform(),
    }
    try:
        import yt_dlp

        payload["yt_dlp"] = getattr(yt_dlp.version, "__version__", "unknown")
    except Exception as exc:  # pragma: no cover - runs only on device
        payload["ok"] = False
        payload["yt_dlp_error"] = f"{type(exc).__name__}: {exc}"
    return json.dumps(payload, ensure_ascii=False)


def probe_url(text: str) -> str:
    url = _first_url(text)
    if not url:
        return json.dumps(
            {"ok": False, "stage": "input", "error": "没有识别到 http/https 链接"},
            ensure_ascii=False,
        )

    site = detect_site(url)
    if site == "douyin":
        from douyin_resolver import resolve

        return resolve(text)
    if site == "bilibili":
        from bilibili_resolver import resolve

        return resolve(text)

    try:
        import yt_dlp

        options = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "noplaylist": True,
            "socket_timeout": 20,
        }
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=False)
        payload = {
            "ok": True,
            "stage": "metadata",
            "site": site,
            "url": url,
            "title": info.get("title"),
            "duration": info.get("duration"),
            "extractor": info.get("extractor_key") or info.get("extractor"),
            "format_count": len(info.get("formats") or []),
            "subtitle_languages": sorted(
                set((info.get("subtitles") or {}).keys())
                | set((info.get("automatic_captions") or {}).keys())
            )[:20],
        }
        return json.dumps(payload, ensure_ascii=False)
    except Exception as exc:  # pragma: no cover - requires live platform access
        return json.dumps(
            {
                "ok": False,
                "stage": "metadata",
                "site": site,
                "url": url,
                "error": f"{type(exc).__name__}: {exc}",
                "trace_tail": traceback.format_exc().splitlines()[-4:],
            },
            ensure_ascii=False,
        )

