"""Resolve a Douyin share text to video or gallery image sources.

The signing primitive is vendored from f2 under Apache-2.0. This module keeps
the Android port deliberately small: stdlib networking, anonymous identifiers,
one detail request, and deterministic resolution-first gear selection.
"""

from __future__ import annotations

import http.cookies
import json
import random
import re
import string
import time
import traceback
import urllib.error
import urllib.request
from typing import Any

import httpx

from vendor.f2_abogus import ABogus, BrowserFingerprintGenerator


UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
    "Safari/537.36 Edg/130.0.0.0"
)
REFERER = "https://www.douyin.com/"
DETAIL_ENDPOINT = "https://www.douyin.com/aweme/v1/web/aweme/detail/"
TTWID_ENDPOINT = "https://ttwid.bytedance.com/ttwid/union/register/"
TTWID_BODY = (
    '{"region":"cn","aid":1768,"needFid":false,'
    '"service":"www.ixigua.com","migrate_info":{"ticket":"",'
    '"source":"node"},"cbUrlProtocol":"https","union":true}'
).encode("utf-8")

URL_RE = re.compile(
    r"https?://(?:v\.douyin\.com/[A-Za-z0-9_-]+/?|"
    r"(?:www\.)?douyin\.com(?:/[^\s\"'<>]*)?)",
    re.IGNORECASE,
)
VIDEO_RE = re.compile(r"douyin\.com/video/(\d{6,})", re.IGNORECASE)
NOTE_RE = re.compile(r"douyin\.com/note/(\d{6,})", re.IGNORECASE)
QUERY_ID_RE = re.compile(
    r"(?:[?&#]|%3[fF]|%26)(?:modal_id|aweme_id)(?:=|%3[dD])(\d{6,})",
    re.IGNORECASE,
)
TRAILING = "，。、；：！？》）】”’\",.;:!?)>]}'"


def _verify_fp() -> str:
    alphabet = string.digits + string.ascii_uppercase + string.ascii_lowercase
    millis = int(time.time() * 1000)
    base36 = ""
    while millis:
        millis, remainder = divmod(millis, 36)
        base36 = (str(remainder) if remainder < 10 else chr(87 + remainder)) + base36
    chars = [""] * 36
    chars[8] = chars[13] = chars[18] = chars[23] = "_"
    chars[14] = "4"
    for index, value in enumerate(chars):
        if value:
            continue
        pick = random.randrange(len(alphabet))
        if index == 19:
            pick = (pick & 3) | 8
        chars[index] = alphabet[pick]
    return "verify_" + base36 + "_" + "".join(chars)


def _false_ms_token() -> str:
    alphabet = string.ascii_letters + string.digits + "-_"
    return "".join(random.choice(alphabet) for _ in range(182)) + "=="


def _first_url(text: str) -> str:
    match = URL_RE.search(text or "")
    return match.group(0).rstrip(TRAILING) if match else ""


def _expand(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.geturl()
    except urllib.error.HTTPError as error:
        final = error.geturl()
        if final and final != url:
            return final
        raise


def _extract_aweme(text: str) -> tuple[str, str, str, str]:
    source = _first_url(text)
    if not source:
        raise ValueError("没有识别到抖音 http/https 链接")
    source_match = VIDEO_RE.search(source) or NOTE_RE.search(source) or QUERY_ID_RE.search(source)
    final = source if source_match else _expand(source)
    video = VIDEO_RE.search(final)
    note = NOTE_RE.search(final)
    query = QUERY_ID_RE.search(final) or QUERY_ID_RE.search(source)
    match = note or video or query
    if not match:
        raise ValueError(
            "网址中没有识别到 /video/<id>、/note/<id>、modal_id 或 aweme_id"
        )
    page_kind = "image" if note else "video" if video else "unknown"
    return source, final, match.group(1), page_kind


def _ttwid() -> str:
    request = urllib.request.Request(
        TTWID_ENDPOINT,
        data=TTWID_BODY,
        method="POST",
        headers={"User-Agent": UA, "Content-Type": "application/json; charset=utf-8"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        cookie = http.cookies.SimpleCookie()
        for header in response.headers.get_all("Set-Cookie") or []:
            cookie.load(header)
        morsel = cookie.get("ttwid")
        if morsel is None or not morsel.value:
            raise RuntimeError("ttwid 接口未返回 ttwid cookie")
        return morsel.value


def _params(aweme_id: str) -> list[tuple[str, str]]:
    return [
        ("device_platform", "webapp"),
        ("aid", "6383"),
        ("channel", "channel_pc_web"),
        ("pc_client_type", "1"),
        ("publish_video_strategy_type", "2"),
        ("pc_libra_divert", "Windows"),
        ("version_code", "290100"),
        ("version_name", "29.1.0"),
        ("cookie_enabled", "true"),
        ("screen_width", "1920"),
        ("screen_height", "1080"),
        ("browser_language", "zh-CN"),
        ("browser_platform", "Win32"),
        ("browser_name", "Edge"),
        ("browser_version", "130.0.0.0"),
        ("browser_online", "true"),
        ("engine_name", "Blink"),
        ("engine_version", "130.0.0.0"),
        ("os_name", "Windows"),
        ("os_version", "10"),
        ("cpu_core_num", "12"),
        ("device_memory", "8"),
        ("platform", "PC"),
        ("downlink", "10"),
        ("effective_type", "4g"),
        ("round_trip_time", "100"),
        ("msToken", _false_ms_token()),
        ("aweme_id", aweme_id),
    ]


def _gears(video: dict[str, Any]) -> list[dict[str, Any]]:
    choices: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in video.get("bit_rate") or []:
        item = item or {}
        address = item.get("play_addr") or {}
        urls = address.get("url_list") or []
        if not urls or urls[0] in seen:
            continue
        seen.add(urls[0])
        width = int(address.get("width") or video.get("width") or 0)
        height = int(address.get("height") or video.get("height") or 0)
        choices.append(
            {
                "urls": urls,
                "size": int(address.get("data_size") or 0),
                "width": width,
                "height": height,
                "pixels": width * height,
                "bit_rate": int(item.get("bit_rate") or 0),
                "gear": str(item.get("gear_name") or ""),
                "h265": bool(int(item.get("is_h265") or 0) or int(item.get("is_bytevc1") or 0)),
            }
        )
    primary = video.get("play_addr") or {}
    primary_urls = primary.get("url_list") or []
    if primary_urls and primary_urls[0] not in seen:
        width = int(video.get("width") or 0)
        height = int(video.get("height") or 0)
        choices.append(
            {
                "urls": primary_urls,
                "size": int(primary.get("data_size") or 0),
                "width": width,
                "height": height,
                "pixels": width * height,
                "bit_rate": int(video.get("bit_rate") or 0),
                "gear": "play_addr",
                "h265": False,
            }
        )
    choices.sort(key=lambda item: (item["pixels"], item["bit_rate"], item["size"]), reverse=True)
    return choices


def _images(detail: dict[str, Any]) -> list[dict[str, Any]]:
    """Return watermark-free gallery sources from the public detail payload.

    Douyin exposes both ``url_list`` and ``download_url_list`` for galleries.
    The latter currently contains the ``tplv-dy-water`` transform, while the
    former contains the regular ``tplv-dy-aweme-images`` transform.  We only
    expose ``url_list`` and keep all of its CDN alternatives for fallback.
    """
    result: list[dict[str, Any]] = []
    for index, image in enumerate(detail.get("images") or []):
        if not isinstance(image, dict):
            continue
        urls = [
            str(url)
            for url in image.get("url_list") or []
            if isinstance(url, str) and url.startswith(("https://", "http://"))
        ]
        # Do not silently fall back to download_url_list: those URLs carry the
        # platform watermark transform and would violate the user's selection.
        if not urls:
            continue
        result.append(
            {
                "index": index,
                "urls": urls,
                "width": int(image.get("width") or 0),
                "height": int(image.get("height") or 0),
                "uri": str(image.get("uri") or ""),
            }
        )
    return result


def _detail(aweme_id: str) -> tuple[dict[str, Any], dict[str, str]]:
    verify_fp = _verify_fp()
    cookie = f"ttwid={_ttwid()}; s_v_web_id={verify_fp};"
    params = _params(aweme_id)
    raw_query = "&".join(f"{key}={value}" for key, value in params)
    fingerprint = BrowserFingerprintGenerator.generate_fingerprint("Edge")
    signature = ABogus(fp=fingerprint, user_agent=UA).generate_abogus(raw_query, "")[1]
    # Match f2 exactly: A-Bogus uses its own URL-safe alphabet and is appended
    # verbatim. Percent-encoding it changes what the gateway verifies.
    url = DETAIL_ENDPOINT + "?" + raw_query + "&a_bogus=" + signature
    headers = {
        "User-Agent": UA,
        "Referer": REFERER,
        "Cookie": cookie,
        "x-tt-argus": "1",
    }
    try:
        response = httpx.get(url, headers=headers, timeout=25, follow_redirects=True)
        response.raise_for_status()
    except httpx.HTTPStatusError as error:
        tail = error.response.text[:300]
        raise RuntimeError(
            f"抖音详情接口 HTTP {error.response.status_code}: {tail}"
        ) from error
    payload = response.json()
    detail = payload.get("aweme_detail") or {}
    if not detail:
        raise RuntimeError("抖音详情接口没有返回 aweme_detail")
    return detail, {"User-Agent": UA, "Referer": REFERER}


def resolve(text: str) -> str:
    """Return signed media metadata as a JSON string for Kotlin."""
    try:
        source, expanded, aweme_id, page_kind = _extract_aweme(text)
        detail, media_headers = _detail(aweme_id)
        images = _images(detail)
        common = {
            "ok": True,
            "stage": "resolved",
            "source_url": source,
            "expanded_url": expanded,
            "aweme_id": aweme_id,
            "desc": detail.get("desc") or "",
            "nickname": (detail.get("author") or {}).get("nickname") or "",
            "headers": media_headers,
        }
        if images:
            result = {
                **common,
                "kind": "image",
                "page_kind": page_kind,
                "images": images,
                "image_count": len(images),
                "watermark_free": True,
                "source_field": "aweme_detail.images[].url_list",
            }
            return json.dumps(result, ensure_ascii=False)

        video = detail.get("video") or {}
        gears = _gears(video)
        if not gears:
            raise RuntimeError("响应中既没有可用图集，也没有可播放的视频档位")
        best = gears[0]
        result = {
            **common,
            "kind": "video",
            "page_kind": page_kind,
            "urls": best["urls"],
            "size": best["size"],
            "width": best["width"],
            "height": best["height"],
            "bit_rate": best["bit_rate"],
            "gear": best["gear"],
            "h265": best["h265"],
            "gear_count": len(gears),
        }
        return json.dumps(result, ensure_ascii=False)
    except Exception as error:
        return json.dumps(
            {
                "ok": False,
                "stage": "douyin_resolve",
                "error": f"{type(error).__name__}: {error}",
                "trace_tail": traceback.format_exc().splitlines()[-6:],
            },
            ensure_ascii=False,
        )
