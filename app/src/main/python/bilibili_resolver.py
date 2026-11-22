"""Resolve one Bilibili video into user-selected video and AAC audio tracks.

The module runs entirely inside the Android app.  yt-dlp handles Bilibili's
WBI signing; Kotlin downloads the two selected DASH tracks and remuxes them
with Android's MediaMuxer.
"""

from __future__ import annotations

import json
import re
import traceback
from typing import Any
from urllib.parse import urlsplit


URL_RE = re.compile(r"https?://[^\s]+", re.IGNORECASE)
FORMAT_EXPR = "bv+ba[acodec^=mp4a]/bv+ba/bv*+ba/b"
FORMAT_ID_RE = re.compile(r"-(\d+)\.m4s(?:\?|$)", re.IGNORECASE)
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


def _candidate_urls(item: dict[str, Any], play_infos: list[dict[str, Any]]) -> list[str]:
    selected_url = str(item.get("url") or "")
    selected_format_id = str(item.get("format_id") or "")
    candidates = [selected_url] if selected_url else []

    raw_tracks: list[dict[str, Any]] = []
    for play_info in play_infos:
        dash = play_info.get("dash") or {}
        raw_tracks.extend(track for track in dash.get("video") or [] if isinstance(track, dict))
        raw_tracks.extend(track for track in dash.get("audio") or [] if isinstance(track, dict))
        dolby = dash.get("dolby") or {}
        raw_tracks.extend(track for track in dolby.get("audio") or [] if isinstance(track, dict))
        flac = dash.get("flac") or {}
        if isinstance(flac.get("audio"), dict):
            raw_tracks.append(flac["audio"])

    def raw_urls(track: dict[str, Any]) -> list[str]:
        base = track.get("baseUrl") or track.get("base_url") or track.get("url")
        backups = track.get("backupUrl") or track.get("backup_url") or []
        if isinstance(backups, str):
            backups = [backups]
        return [str(value) for value in [base, *backups] if value]

    def format_ids(track: dict[str, Any]) -> set[str]:
        values = {str(track.get("id") or "")}
        for value in raw_urls(track):
            if match := FORMAT_ID_RE.search(value):
                values.add(match.group(1))
        return values

    matching = [
        track for track in raw_tracks
        if selected_url in raw_urls(track)
        or (selected_format_id and selected_format_id in format_ids(track))
    ]
    for track in matching:
        candidates.extend(raw_urls(track))

    unique = list(dict.fromkeys(value for value in candidates if value))

    def route_priority(value: str) -> tuple[int, int]:
        parsed = urlsplit(value)
        host = (parsed.hostname or "").lower()
        nonstandard_port = parsed.port not in (None, 443)
        unreliable_mcdn = ".mcdn." in host or host.endswith("mcdn.bilivideo.cn")
        return (int(unreliable_mcdn or nonstandard_port), int(value != selected_url))

    return sorted(unique, key=route_priority)


def _track_payload(
    item: dict[str, Any],
    play_infos: list[dict[str, Any]],
) -> dict[str, Any]:
    urls = _candidate_urls(item, play_infos)
    return {
        "url": urls[0] if urls else item.get("url") or "",
        "urls": urls,
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


def _codec_family(codec: Any) -> str:
    normalized = str(codec or "").lower()
    if normalized.startswith(("avc", "h264")):
        return "h264"
    if normalized.startswith(("hev", "hvc", "h265")):
        return "h265"
    if normalized.startswith(("av01", "av1")):
        return "av1"
    return normalized or "unknown"


def resolve(text: str, quality: str = "best", codec: str = "h264") -> str:
    url = _first_url(text)
    if not url:
        return json.dumps(
            {"ok": False, "stage": "input", "error": "没有识别到 B 站链接"},
            ensure_ascii=False,
        )
    lowered = url.lower()
    if "bilibili.com" not in lowered and "b23.tv" not in lowered:
        return json.dumps(
            {"ok": False, "stage": "input", "error": "这不是 B 站链接"},
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
        from yt_dlp.extractor.bilibili import BilibiliBaseIE

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
        play_infos: list[dict[str, Any]] = []
        original_download_playinfo = BilibiliBaseIE._download_playinfo

        def capture_playinfo(extractor: Any, *args: Any, **kwargs: Any) -> Any:
            play_info = original_download_playinfo(extractor, *args, **kwargs)
            if isinstance(play_info, dict):
                play_infos.append(play_info)
            return play_info

        BilibiliBaseIE._download_playinfo = capture_playinfo
        try:
            with yt_dlp.YoutubeDL(options) as ydl:
                info = ydl.extract_info(url, download=False)
        finally:
            BilibiliBaseIE._download_playinfo = original_download_playinfo

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
            raise RuntimeError("B 站未返回可合并的视频轨和音频轨")
        if not video.get("url") or not audio.get("url"):
            raise RuntimeError("B 站轨道缺少下载地址")

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
            "site": "bilibili",
            "source_url": url,
            "webpage_url": info.get("webpage_url") or url,
            "id": info.get("id") or "bilibili",
            "title": info.get("title") or "B站视频",
            "uploader": info.get("uploader") or "",
            "duration": info.get("duration") or 0,
            "requested_quality": quality,
            "requested_codec": codec,
            "actual_height": actual_height,
            "actual_codec": actual_codec,
            "quality_met": quality_met,
            "codec_met": codec_met,
            "selection_summary": f"请求 {requested_label}；实际 {actual_label}",
            "selection_warning": selection_warning,
            "video": _track_payload(video, play_infos),
            "audio": _track_payload(audio, play_infos),
        }
        return json.dumps(payload, ensure_ascii=False)
    except Exception as exc:  # pragma: no cover - requires live platform access
        return json.dumps(
            {
                "ok": False,
                "stage": "resolve",
                "site": "bilibili",
                "url": url,
                "error": f"{type(exc).__name__}: {exc}",
                "trace_tail": traceback.format_exc().splitlines()[-5:],
            },
            ensure_ascii=False,
        )
