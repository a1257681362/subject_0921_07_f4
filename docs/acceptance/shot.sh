#!/usr/bin/env bash
# 把验收步骤的命令+输出实录渲染成终端风格截图 PNG。
# 用法：shot.sh <输出png> <标题> <实录文本文件>
# 注意：IM6 的 label/caption 对超长文本渲染会失败，按行切成 ≤900 字节的块分别渲染再纵向拼接。
set -euo pipefail
OUT="$1"; TITLE="$2"; TXT="$3"
FONT=/home/claude/.fonts/wqy-microhei.ttc
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

python3 - "$TXT" "$TMP" <<'PY'
import sys, os
src, tmp = sys.argv[1], sys.argv[2]
chunk, size, idx = [], 0, 0
def flush():
    global chunk, size, idx
    if chunk:
        with open(os.path.join(tmp, 'c%03d.txt' % idx), 'w', encoding='utf-8') as f:
            f.write('\n'.join(chunk))
        idx += 1
        chunk, size = [], 0
for line in open(src, encoding='utf-8'):
    line = line.rstrip('\n')
    n = len(line.encode('utf-8')) + 1
    if size + n > 900:
        flush()
    chunk.append(line)
    size += n
flush()
PY

IMGS=()
convert -size 1080x -background '#2d2d2d' -fill '#ffffff' -font "$FONT" -pointsize 15 \
        -gravity west caption:"  $TITLE" "$TMP/title.png"
IMGS+=("$TMP/title.png")
for c in "$TMP"/c*.txt; do
  png="${c%.txt}.png"
  convert -size 1080x -background '#1e1e1e' -fill '#d4d4d4' -font "$FONT" -pointsize 13 \
          -gravity northwest -interline-spacing 4 caption:"$(cat "$c")" "$png"
  IMGS+=("$png")
done
convert "${IMGS[@]}" -append -bordercolor '#1e1e1e' -border 8 "$OUT"
echo "shot -> $OUT"
