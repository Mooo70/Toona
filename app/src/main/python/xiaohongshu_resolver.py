"""Resolve one public Xiaohongshu/RedNote share URL without an account cookie.

The resolver only reads the server-rendered ``window.__INITIAL_STATE__`` from the
public work page. It does not call signed APIs, search, profiles, comments, or
private/login-only surfaces.
"""

from __future__ import annotations

import html
import http.cookiejar
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


URL_RE = re.compile(
    r"https?://(?:www\.)?(?:xiaohongshu\.com|rednote\.com|xhslink\.(?:com|cn))"
    r"/[^\s\"<>\\^`{|}，。；！？、【】《》]+",
    re.IGNORECASE,
)
NOTE_ID_RE = re.compile(
    r"/(?:explore|discovery/item|user/profile/[0-9a-z]+)/(?:[^/?#]+/)?([0-9a-f]{16,32})",
    re.IGNORECASE,
)
INITIAL_STATE_RE = re.compile(
    r"window\.__INITIAL_STATE__\s*=\s*(.*?)</script\s*>",
    re.IGNORECASE | re.DOTALL,
)
ALLOWED_PAGE_HOSTS = {
    "www.xiaohongshu.com",
    "xiaohongshu.com",
    "www.rednote.com",
    "rednote.com",
    "xhslink.com",
    "www.xhslink.com",
    "xhslink.cn",
    "www.xhslink.cn",
}
ALLOWED_CDN_SUFFIXES = (".xhscdn.com", ".xiaohongshu.com")
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 16; 25129PN74C) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/140.0.0.0 Mobile Safari/537.36"
)


class ResolveError(RuntimeError):
    pass


def _extract_url(text: str) -> str:
    match = URL_RE.search(text or "")
    if not match:
        raise ResolveError("没有找到小红书链接；请粘贴分享文案或完整网址")
    url = html.unescape(match.group(0)).rstrip(".,;:!?，。；！？、)")
    parsed = urllib.parse.urlsplit(url)
    if parsed.hostname and parsed.hostname.lower() not in ALLOWED_PAGE_HOSTS:
        raise ResolveError("链接不是受支持的小红书域名")
    return url


def _request_headers(referer: str | None = None) -> dict[str, str]:
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.7",
        "Cache-Control": "no-cache",
        "Pragma": "no-cache",
    }
    if referer:
        headers["Referer"] = referer
    return headers


def _open_page(url: str) -> tuple[str, str]:
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    request = urllib.request.Request(url, headers=_request_headers())
    try:
        with opener.open(request, timeout=22) as response:
            final_url = response.geturl()
            raw = response.read(8 * 1024 * 1024 + 1)
    except urllib.error.HTTPError as error:
        raise ResolveError(f"小红书页面 HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise ResolveError(f"连接小红书失败：{error.reason}") from error
    if len(raw) > 8 * 1024 * 1024:
        raise ResolveError("小红书页面数据异常过大")
    final_host = (urllib.parse.urlsplit(final_url).hostname or "").lower()
    if final_host not in ALLOWED_PAGE_HOSTS:
        raise ResolveError("小红书短链跳转到了不受支持的域名")
    return raw.decode("utf-8", "replace"), final_url


def _parse_initial_state(page: str) -> dict[str, Any]:
    match = INITIAL_STATE_RE.search(page)
    if not match:
        raise ResolveError("页面未返回作品数据；请复制一条最新的公开分享链接")
    source = match.group(1).strip().removesuffix(";")
    source = source.replace("new Map([])", "[]").replace("new Set([])", "[]")
    source = re.sub(r"(?<=:)undefined(?=\s*[,}])", "null", source)
    try:
        value = json.loads(source)
    except json.JSONDecodeError as error:
        raise ResolveError("小红书页面数据格式发生变化") from error
    if not isinstance(value, dict):
        raise ResolveError("小红书页面没有有效作品数据")
    return value


def _note_id(url: str) -> str:
    decoded = urllib.parse.unquote(url)
    match = NOTE_ID_RE.search(decoded)
    return match.group(1) if match else ""


def _find_note(state: dict[str, Any], expected_id: str) -> dict[str, Any]:
    detail_map = state.get("note", {}).get("noteDetailMap", {})
    if isinstance(detail_map, dict):
        candidates: list[Any] = []
        if expected_id:
            candidates.append(detail_map.get(expected_id))
        candidates.extend(detail_map.values())
        for candidate in candidates:
            if isinstance(candidate, dict) and isinstance(candidate.get("note"), dict):
                note = candidate["note"]
                if not expected_id or note.get("noteId") in (None, "", expected_id):
                    return note

    phone = state.get("noteData", {}).get("data", {}).get("noteData")
    if isinstance(phone, dict):
        return phone
    raise ResolveError("作品已失效、不可公开访问，或分享令牌已过期")


def _valid_cdn_url(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    value = value.replace("\\u002F", "/").replace("\\/", "/")
    parsed = urllib.parse.urlsplit(value)
    host = (parsed.hostname or "").lower()
    if parsed.scheme not in {"http", "https"}:
        return None
    if not any(host.endswith(suffix) for suffix in ALLOWED_CDN_SUFFIXES):
        return None
    if parsed.scheme == "http":
        value = urllib.parse.urlunsplit(("https", parsed.netloc, parsed.path, parsed.query, parsed.fragment))
    return value


def _image_token(item: dict[str, Any]) -> str:
    token = item.get("fileId")
    if isinstance(token, str) and token:
        return token
    for key in ("urlDefault", "urlPre", "url"):
        value = _valid_cdn_url(item.get(key))
        if not value:
            continue
        path = urllib.parse.urlsplit(value).path.lstrip("/").split("!")[0]
        if path:
            segments = path.split("/")
            # Current web image URLs prepend two routing segments which are not
            # part of the portable file token used by the original-image CDN.
            return "/".join(segments[2:]) if len(segments) >= 3 else path
    return ""


def _images(note: dict[str, Any]) -> list[dict[str, Any]]:
    result = []
    for index, item in enumerate(note.get("imageList") or []):
        if not isinstance(item, dict):
            continue
        urls: list[str] = []
        token = _image_token(item)
        if token:
            urls.append(f"https://sns-img-bd.xhscdn.com/{token}")
        for key in ("urlDefault", "urlPre", "url"):
            if value := _valid_cdn_url(item.get(key)):
                urls.append(value)
        urls = list(dict.fromkeys(urls))
        if urls:
            image = {
                "index": index,
                "urls": urls,
                "width": int(item.get("width") or 0),
                "height": int(item.get("height") or 0),
            }
            live_candidates: list[dict[str, Any]] = []
            streams = item.get("stream") or {}
            nested_live = item.get("livePhoto")
            if (not isinstance(streams, dict) or not streams) and isinstance(nested_live, dict):
                streams = ((nested_live.get("media") or {}).get("stream") or {})
            if isinstance(streams, dict):
                for codec, codec_items in streams.items():
                    if not isinstance(codec_items, list):
                        continue
                    for stream in codec_items:
                        if not isinstance(stream, dict):
                            continue
                        stream_urls: list[str] = []
                        if value := _valid_cdn_url(stream.get("masterUrl")):
                            stream_urls.append(value)
                        for value in stream.get("backupUrls") or []:
                            if valid := _valid_cdn_url(value):
                                stream_urls.append(valid)
                        if stream_urls:
                            live_candidates.append(
                                {
                                    "urls": list(dict.fromkeys(stream_urls)),
                                    "width": int(stream.get("width") or 0),
                                    "height": int(stream.get("height") or 0),
                                    "duration": int(stream.get("duration") or 0),
                                    "size": int(stream.get("size") or 0),
                                    "codec": str(stream.get("videoCodec") or codec or ""),
                                    "stream_type": int(stream.get("streamType") or 0),
                                    "stream_desc": str(stream.get("streamDesc") or ""),
                                }
                            )
            live_candidates.sort(
                key=lambda value: (
                    str(value["codec"]).lower() in {"h264", "avc"},
                    value["height"] * value["width"],
                    value["size"],
                ),
                reverse=True,
            )
            if live_candidates and bool(item.get("livePhoto")):
                live = live_candidates[0]
                image.update(
                    {
                        "live_photo": True,
                        "live_urls": live["urls"],
                        "live_width": live["width"],
                        "live_height": live["height"],
                        "live_duration": live["duration"],
                        "live_size": live["size"],
                        "live_codec": live["codec"],
                        "live_stream_type": live["stream_type"],
                        "live_stream_desc": live["stream_desc"],
                    }
                )
            result.append(image)
    return result


def _video_candidates(note: dict[str, Any]) -> tuple[list[str], dict[str, Any]]:
    video = note.get("video") or {}
    origin_key = (video.get("consumer") or {}).get("originVideoKey")
    candidates: list[dict[str, Any]] = []
    streams = ((video.get("media") or {}).get("stream") or {})
    if isinstance(streams, dict):
        for codec_items in streams.values():
            if not isinstance(codec_items, list):
                continue
            for item in codec_items:
                if not isinstance(item, dict):
                    continue
                urls = []
                if value := _valid_cdn_url(item.get("masterUrl")):
                    urls.append(value)
                for value in item.get("backupUrls") or []:
                    if valid := _valid_cdn_url(value):
                        urls.append(valid)
                if urls:
                    candidates.append(
                        {
                            "urls": list(dict.fromkeys(urls)),
                            "width": int(item.get("width") or 0),
                            "height": int(item.get("height") or 0),
                            "bitrate": int(item.get("videoBitrate") or item.get("avgBitrate") or 0),
                            "size": int(item.get("size") or 0),
                            "codec": str(item.get("videoCodec") or ""),
                        }
                    )
    candidates.sort(
        key=lambda item: (item["height"], item["width"], item["bitrate"], item["size"]),
        reverse=True,
    )
    best = candidates[0] if candidates else {}
    urls: list[str] = []
    if isinstance(origin_key, str) and origin_key:
        urls.append(f"https://sns-video-bd.xhscdn.com/{origin_key}")
    urls.extend(best.get("urls") or [])
    return list(dict.fromkeys(urls)), {
        "width": best.get("width", 0),
        "height": best.get("height", 0),
        "size": best.get("size", 0),
        "codec": best.get("codec", ""),
        "origin_source": bool(origin_key),
    }


def _resolve_from_page(page: str, final_url: str) -> dict[str, Any]:
    parsed = urllib.parse.urlsplit(final_url)
    if parsed.path.startswith(("/404", "/login")):
        raise ResolveError("分享链接已失效或触发访问限制；请从小红书重新复制最新分享链接")
    expected_id = _note_id(final_url)
    state = _parse_initial_state(page)
    note = _find_note(state, expected_id)
    note_id = str(note.get("noteId") or expected_id)
    if not note_id:
        raise ResolveError("没有识别到小红书作品 ID")

    user = note.get("user") or {}
    common: dict[str, Any] = {
        "ok": True,
        "platform": "xiaohongshu",
        "note_id": note_id,
        "title": str(note.get("title") or ""),
        "desc": str(note.get("desc") or note.get("title") or ""),
        "nickname": str(user.get("nickname") or user.get("nickName") or ""),
        "page_url": final_url,
        "headers": {
            "User-Agent": USER_AGENT,
            "Referer": final_url,
        },
    }

    video_urls, video_info = _video_candidates(note)
    if str(note.get("type") or "").lower() == "video" and video_urls:
        return {
            **common,
            "kind": "video",
            "urls": video_urls,
            **video_info,
        }

    images = _images(note)
    if images:
        return {**common, "kind": "image", "images": images}
    if video_urls:
        return {
            **common,
            "kind": "video",
            "urls": video_urls,
            **video_info,
        }
    raise ResolveError("作品页面没有可下载的视频或图片源")


def resolve(share_text: str) -> str:
    try:
        url = _extract_url(share_text)
        page, final_url = _open_page(url)
        return json.dumps(_resolve_from_page(page, final_url), ensure_ascii=False)
    except ResolveError as error:
        return json.dumps({"ok": False, "error": str(error)}, ensure_ascii=False)
    except Exception as error:  # Keep a stable JSON boundary for Chaquopy.
        return json.dumps(
            {"ok": False, "error": f"小红书解析异常：{type(error).__name__}: {error}"},
            ensure_ascii=False,
        )

