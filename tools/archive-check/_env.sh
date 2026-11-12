#!/bin/sh
# 环境探测（一次性）：WSL 里可用的归档工具与下载能力
for c in xz bzip2 7z 7za 7zz unrar zstd python3 pip3 curl; do
  p=$(command -v "$c" 2>/dev/null)
  if [ -n "$p" ]; then printf '%-8s yes  %s\n' "$c" "$p"; else printf '%-8s no\n' "$c"; fi
done
echo "--- python 模块:"
python3 - <<'PY'
for m in ("py7zr", "rarfile", "lzma", "bz2", "zstandard"):
    try:
        __import__(m); print("  %-10s yes" % m)
    except Exception:
        print("  %-10s no" % m)
PY
echo "--- 下载:"
for u in https://www.7-zip.org/a/7z2409-src.tar.xz https://www.7-zip.org/a/7z2500-src.tar.xz https://www.7-zip.org/a/7z2408-src.tar.xz; do
  printf '%-46s ' "$u"; curl -sS -m 20 -o /dev/null -w '%{http_code}\n' -L "$u" 2>&1 | tail -1
done
