#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate a verified CCTV-only live source list (TVBox txt format).

Candidate pool
    1. CCTV official low-latency CDN (ldncctvwbcd) - free of the 央视频 guard.
       Measured coverage: CCTV-1 and CCTV-13 only.
    2. China Mobile IPTV tsfile lines, one entry per regional node.
    3. Public lines mined from live files already hosted on tvbox.moliys.icu.

Every candidate is probed end to end (master -> media playlist -> first
segment) and is only kept when it really plays a video payload. Output is the
plain TVBox "name,#genre#" text format consumed by LiveParser.txt().

Only the Python standard library is used: the script is meant to run on the
server through a Bt panel scheduled task.
"""

import argparse
import concurrent.futures
import os
import re
import socket
import ssl
import sys
import time
from urllib.parse import quote, urljoin
from urllib.request import Request, urlopen

TIMEOUT = 10
MAX_BYTES = 256 * 1024
SEGMENT_PROBE = 8192
WORKERS = 16
MAX_URLS_PER_CHANNEL = 4
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

BAD_TYPES = ("image/", "text/", "application/json", "application/xml", "font/")

CCTV_NAMES = {
    1: "CCTV-1", 2: "CCTV-2", 3: "CCTV-3", 4: "CCTV-4", 5: "CCTV-5",
    6: "CCTV-6", 7: "CCTV-7", 8: "CCTV-8", 9: "CCTV-9", 10: "CCTV-10",
    11: "CCTV-11", 12: "CCTV-12", 13: "CCTV-13", 14: "CCTV-14",
    15: "CCTV-15", 16: "CCTV-16", 17: "CCTV-17",
}
CCTV_WORDS = (
    ("综合", 1), ("财经", 2), ("综艺", 3), ("中文国际", 4), ("体育", 5),
    ("电影", 6), ("国防军事", 7), ("电视剧", 8), ("纪录", 9), ("科教", 10),
    ("戏曲", 11), ("社会与法", 12), ("新闻", 13), ("少儿", 14), ("音乐", 15),
    ("奥林匹克", 16), ("农业农村", 17),
)

OFFICIAL_HOSTS = [
    "ldncctvwbcdtxy.liveplay.myqcloud.com",
    "ldncctvwbcdali.v.myalicdn.com",
    "ldncctvwbcdbd.a.bdydns.com",
    "ldncctvwbcdcnc.v.wscdns.com",
    "ldncctvwbcdks.v.kcdnvip.com",
    "ldncctvwbcdhwy.cntv.myhwcdn.cn",
]
OFFICIAL_PATH = "/ldncctvwbcd/cdrmldcctv{n}_1/index.m3u8"

MOBILE_NODES = [
    "139.227.221.147:9901",
    "112.30.73.119:229",
    "113.25.252.226:9901",
    "124.165.251.82:85",
]
MOBILE_PATH = "/tsfile/live/{n:04d}_1.m3u8?key=txiptv"

MINED_SOURCES = [
    "https://tvbox.moliys.icu/tvbox/live/综合直播.php",
    "https://tvbox.moliys.icu/tvbox/live/优选.txt",
    "https://tvbox.moliys.icu/tvbox/live/电视直播.txt",
    "https://tvbox.moliys.icu/tvbox/live/Kimentanm.m3u",
    "https://tvbox.moliys.icu/tvbox/live/Guovin.m3u",
]

PRIORITY_OFFICIAL = 100
PRIORITY_MOBILE = 90
PRIORITY_MINED = 40

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

socket.setdefaulttimeout(TIMEOUT)


def split_spec(spec):
    """Split "url|Key=Value&Key2=Value2" into (url, headers)."""
    if "|" not in spec:
        return spec.strip(), {}
    url, rest = spec.split("|", 1)
    headers = {}
    for item in rest.split("&"):
        if "=" in item:
            key, value = item.split("=", 1)
            headers[key.strip()] = value.strip()
    return url.strip(), headers


def http_get(url, rng=None, headers=None):
    """GET a URL and return at most MAX_BYTES of the body plus its type."""
    safe = quote(url, safe=":/?&=%#@!$'()*+,;~[]|")
    sent = {"User-Agent": USER_AGENT, "Accept": "*/*"}
    if headers:
        sent.update(headers)
    if rng:
        sent["Range"] = rng
    req = Request(safe, headers=sent)
    with urlopen(req, timeout=TIMEOUT, context=CTX) as resp:
        if resp.status not in (200, 206):
            raise IOError("http %s" % resp.status)
        ctype = (resp.headers.get("Content-Type") or "").lower()
        return resp.read(MAX_BYTES), ctype


def playlist_first(lines):
    """Return the first variant URL found in a master playlist."""
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF") and i + 1 < len(lines):
            return lines[i + 1].strip()
    return ""


def first_segment(text):
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            return line
    return ""


def is_bad_type(ctype):
    return any(ctype.startswith(bad) for bad in BAD_TYPES)


def probe(spec):
    """End to end probe: master -> media playlist -> first segment.

    Returns (ok, detail). detail is a short human readable reason.
    """
    base, headers = split_spec(spec)
    try:
        body, ctype = http_get(base, headers=headers)
    except Exception as exc:
        return False, "fetch fail: %s" % exc
    if not body:
        return False, "empty body"
    text = body.decode("utf-8", "replace")
    if "#EXTM3U" not in text:
        if is_bad_type(ctype):
            return False, "bad type %s" % ctype
        if len(body) < 1024:
            return False, "short binary %s" % len(body)
        return True, "direct stream %sB" % len(body)

    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    variant = playlist_first(lines)
    media_url = base
    media_text = text
    if variant:
        media_url = urljoin(base, variant)
        try:
            raw, _ = http_get(media_url, headers=headers)
            media_text = raw.decode("utf-8", "replace")
        except Exception as exc:
            return False, "variant fail: %s" % exc
        if "#EXTM3U" not in media_text:
            return False, "variant not m3u8"
        lines = [ln.strip() for ln in media_text.splitlines() if ln.strip()]
    if not any(ln.startswith("#EXTINF") for ln in lines):
        return False, "no #EXTINF"

    segment = first_segment(media_text)
    if not segment:
        return False, "no segment"
    seg_url = urljoin(media_url, segment)
    try:
        chunk, seg_type = http_get(seg_url, rng="bytes=0-%d" % (SEGMENT_PROBE - 1), headers=headers)
    except Exception as exc:
        return False, "segment fail: %s" % exc
    if is_bad_type(seg_type):
        return False, "segment type %s" % seg_type
    if len(chunk) < 1024:
        return False, "short segment %sB" % len(chunk)
    return True, "ok %sB" % len(chunk)


def canon_channel(name):
    """Map a raw channel name to a canonical CCTV-* name, or None."""
    if not name:
        return None
    text = name.strip()
    key = re.sub(r"[\s_\-（）()【】\[\]]", "", text).lower()
    if "cgtn" in key or "cctvplus" in key:
        return None
    if "4k" in key or "8k" in key or "超高清" in key:
        return None
    if "+" in key or "plus" in key:
        return None
    match = re.search(r"cctv(\d{1,2})", key)
    if match:
        return CCTV_NAMES.get(int(match.group(1)))
    if "央视" in text or "中央" in text:
        for word, num in CCTV_WORDS:
            if word in text:
                return CCTV_NAMES.get(num)
    return None


def build_official():
    out = []
    for num in sorted(CCTV_NAMES):
        for host in OFFICIAL_HOSTS:
            url = "https://%s%s" % (host, OFFICIAL_PATH.format(n=num))
            out.append((CCTV_NAMES[num], url, url, PRIORITY_OFFICIAL))
    return out


def build_mobile():
    out = []
    for num in range(1, 18):
        for node in MOBILE_NODES:
            url = "http://%s%s" % (node, MOBILE_PATH.format(n=num))
            out.append((CCTV_NAMES[num], url, url, PRIORITY_MOBILE))
    return out


EXTINF_UA = re.compile(r'http-user-agent="(.+?)"')
EXTINF_REF = re.compile(r'http-refer(?:er|rer)="(.+?)"')


def extra_headers(line):
    """Build the TVBox per-url header suffix from an #EXTINF line."""
    items = []
    ua = EXTINF_UA.search(line)
    ref = EXTINF_REF.search(line)
    if ua:
        items.append("User-Agent=%s" % ua.group(1))
    if ref:
        items.append("Referer=%s" % ref.group(1))
    return "|" + "&".join(items) if items else ""


def mine_text(text):
    """Yield (name, spec) pairs from a file mixing m3u8 and txt conventions.

    Several live files on the server start with a stray #EXTM3U and then list
    plain "name,url" lines, so both shapes must be handled in the same pass.
    """
    pending = ""
    pending_headers = ""
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("#"):
            if line.startswith("#EXTINF"):
                pending = line.rsplit(",", 1)[-1].strip()
                pending_headers = extra_headers(line)
            continue
        if "://" not in line:
            continue
        if not line.startswith("http") and "," in line:
            name, rest = line.split(",", 1)
            for spec in rest.split("#"):
                if "://" in spec:
                    yield name, spec
            pending = ""
            pending_headers = ""
            continue
        for spec in line.split("#"):
            if "://" in spec:
                yield pending, spec + pending_headers
        pending = ""
        pending_headers = ""


def build_mined():
    out = []
    for src in MINED_SOURCES:
        try:
            text = http_get(src)[0].decode("utf-8", "replace")
        except Exception as exc:
            print("[warn] mine %s: %s" % (src, exc), file=sys.stderr)
            continue
        for name, spec in mine_text(text):
            channel = canon_channel(name)
            if not channel:
                continue
            url, _ = split_spec(spec)
            out.append((channel, url, spec, PRIORITY_MINED))
    return out


def dedupe(candidates):
    seen = set()
    out = []
    for channel, url, spec, priority in candidates:
        key = (channel, url)
        if key in seen:
            continue
        seen.add(key)
        out.append((channel, url, spec, priority))
    return out


def verify_round(candidates):
    """Probe every candidate concurrently; return (channel, url, spec, pri, ok, detail)."""
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = {pool.submit(probe, spec): (channel, url, spec, priority)
                   for channel, url, spec, priority in candidates}
        for future in concurrent.futures.as_completed(futures):
            channel, url, spec, priority = futures[future]
            ok, detail = future.result()
            results.append((channel, url, spec, priority, ok, detail))
    return results


def verify_all(candidates):
    """Probe once, then retry the failures once: CDN flakiness is common."""
    results = verify_round(candidates)
    retry = [(c, u, s, p) for c, u, s, p, ok, _ in results if not ok]
    if retry:
        again = {(c, u): (ok, detail) for c, u, s, p, ok, detail in verify_round(retry)}
        fixed = []
        for channel, url, spec, priority, ok, detail in results:
            if not ok and (channel, url) in again:
                ok, detail = again[(channel, url)]
            fixed.append((channel, url, spec, priority, ok, detail))
        results = fixed
    return results


def render(results):
    """Build the txt body, best line first, capped per channel."""
    best = {}
    for channel, url, spec, priority, ok, detail in results:
        if not ok:
            continue
        best.setdefault(channel, []).append((priority, spec, detail))

    order = [CCTV_NAMES[n] for n in sorted(CCTV_NAMES)]
    lines = ["央视频道,#genre#"]
    report = []
    for channel in order:
        bucket = best.get(channel)
        if not bucket:
            continue
        bucket.sort(key=lambda item: -item[0])
        specs = []
        for priority, spec, detail in bucket:
            if spec in specs:
                continue
            specs.append(spec)
            if len(specs) >= MAX_URLS_PER_CHANNEL:
                break
        lines.append("%s,%s" % (channel, "#".join(specs)))
        report.append((channel, len(specs)))
    return "\n".join(lines) + "\n", report


def main():
    parser = argparse.ArgumentParser(description="生成央视直播源（逐条校验，能播才留）")
    parser.add_argument("--out", default="央视.txt", help="输出文件路径")
    parser.add_argument("--official-only", action="store_true", help="只用官方直链与移动线路")
    parser.add_argument("--limit", type=int, default=0, help="只校验前 N 个候选（调试用）")
    args = parser.parse_args()

    started = time.time()
    candidates = build_official() + build_mobile()
    if not args.official_only:
        candidates += build_mined()
    candidates = dedupe(candidates)
    if args.limit:
        candidates = candidates[:args.limit]
    print("候选 %d 条，开始校验..." % len(candidates))

    results = verify_all(candidates)
    ok = [item for item in results if item[4]]
    print("可播 %d / %d 条，耗时 %.1fs" % (len(ok), len(results), time.time() - started))

    body, report = render(results)
    body = "更新时间," + time.strftime("%Y-%m-%d %H:%M:%S") + "\n" + body
    with open(args.out, "w", encoding="utf-8") as fp:
        fp.write(body)
    print("已写入 %s（%d 个频道）" % (os.path.abspath(args.out), len(report)))
    for channel, count in report:
        print("  %-9s %d 条线路" % (channel, count))


if __name__ == "__main__":
    main()
