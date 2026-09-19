#!/usr/bin/env python3
"""重打 site.pak：拉取服务器最新检测数据，可替换更新清单地址，再打包。

用法:
    python3 tools/repack_site.py \
        --site-dir site-src \
        --out app/src/main/assets/site.pak \
        --manifest-url https://tvbox.moliys.icu/apk/version-lite-official.js

--no-fetch 跳过联网，直接用仓库内已有数据打包（离线回退）。
"""
import argparse
import json
import os
import re
import struct
import zlib
import urllib.request

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SITEKEYS = os.path.join(BASE, 'app/src/main/java/com/moliys/tvbox/SiteKeys.java')
HEALTH_URL = 'https://tvbox.moliys.icu/health.json'
CAI_URL = 'https://tvbox.moliys.icu/data/cai-health.json'
MAGIC = b'MSITEPK2'


def read_key():
    with open(SITEKEYS, 'r', encoding='utf-8') as f:
        m = re.search(r'SITE_KEY_HEX\s*=\s*"([0-9a-fA-F]+)"', f.read())
    if not m:
        raise SystemExit('SITE_KEY_HEX not found in ' + SITEKEYS)
    return bytes.fromhex(m.group(1))


def fetch(url, dest):
    req = urllib.request.Request(url, headers={'User-Agent': 'moliys-repack/1.0'})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read()
    obj = json.loads(data.decode('utf-8'))
    if not obj.get('generated_at') and not obj.get('sites'):
        raise SystemExit('unexpected payload from ' + url)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, 'wb') as f:
        f.write(data)
    print('fetched %s -> %s (%d bytes)' % (url, dest, len(data)))


def patch_manifest(site_dir, url):
    path = os.path.join(site_dir, 'index.html')
    with open(path, 'r', encoding='utf-8') as f:
        src = f.read()
    new, n = re.subn(r"var UPDATE_MANIFEST_URL = '[^']*';",
                     "var UPDATE_MANIFEST_URL = '" + url + "';", src, count=1)
    if n == 0:
        raise SystemExit('UPDATE_MANIFEST_URL not found in ' + path)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(new)
    print('patched UPDATE_MANIFEST_URL -> ' + url)


def walk_files(src):
    out = []
    for base, _dirs, names in os.walk(src):
        for name in names:
            p = os.path.join(base, name)
            out.append((os.path.relpath(p, src).replace(os.sep, '/'), p))
    out.sort(key=lambda x: x[0])
    return out


def pack(src, out):
    from cryptography.hazmat.primitives import padding
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    key = read_key()
    files = walk_files(src)
    entries = []
    payload = bytearray()
    for rel, p in files:
        with open(p, 'rb') as fp:
            raw = fp.read()
        data = zlib.compress(raw, 9)
        iv = os.urandom(16)
        padder = padding.PKCS7(128).padder()
        padded = padder.update(data) + padder.finalize()
        enc = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
        ct = enc.update(padded) + enc.finalize()
        entries.append((rel, len(payload), len(ct), iv))
        payload += ct
    head = bytearray(MAGIC)
    head += struct.pack('>I', len(entries))
    for rel, off, ln, iv in entries:
        name = rel.encode('utf-8')
        head += struct.pack('>H', len(name)) + name + struct.pack('>II', off, ln) + iv
    os.makedirs(os.path.dirname(out), exist_ok=True)
    tmp = out + '.tmp'
    with open(tmp, 'wb') as f:
        f.write(bytes(head))
        f.write(bytes(payload))
    os.replace(tmp, out)
    print('packed %d files -> %s (%d bytes)' % (len(entries), out, len(head) + len(payload)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--site-dir', default='site-src')
    ap.add_argument('--out', default='app/src/main/assets/site.pak')
    ap.add_argument('--manifest-url', default='')
    ap.add_argument('--no-fetch', action='store_true')
    args = ap.parse_args()
    site_dir = args.site_dir if os.path.isabs(args.site_dir) else os.path.join(BASE, args.site_dir)
    out = args.out if os.path.isabs(args.out) else os.path.join(BASE, args.out)
    if not args.no_fetch:
        fetch(HEALTH_URL, os.path.join(site_dir, 'health.json'))
        fetch(CAI_URL, os.path.join(site_dir, 'data/cai-health.json'))
    if args.manifest_url:
        patch_manifest(site_dir, args.manifest_url)
    pack(site_dir, out)


if __name__ == '__main__':
    main()
