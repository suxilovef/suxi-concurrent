#!/usr/bin/env bash
# 知识树 mermaid → SVG 导出脚本
# 用法：
#   bash scripts/export-mermaid.sh                   # 导出 docs/ 下所有 md 的 mermaid 块
#   bash scripts/export-mermaid.sh docs/90-xxx.md    # 只导出指定文档
# 产物：docs/assets/<文档名>[-图N].svg（单个 mermaid 块省略 -图N）
# 依赖：node + npx；浏览器路径见 .tmp-mermaid/pp.json（本机 Edge/Chrome，换机器需改）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -f "$1" ]]; then
  TARGETS=("$1")
  DOCS_DIR="docs"
else
  DOCS_DIR="${1:-docs}"
  TARGETS=("$DOCS_DIR"/*.md)
fi

OUT_DIR="$DOCS_DIR/assets"
TMP_DIR=".tmp-mermaid"
PP_CONFIG="$TMP_DIR/pp.json"
mkdir -p "$OUT_DIR" "$TMP_DIR"

# 首次调用会经 npx 下载 mermaid-cli（之后走 npx 本地缓存）
if ! npx -y -p @mermaid-js/mermaid-cli mmdc --version >/dev/null 2>&1; then
  echo "mermaid-cli 不可用，请检查 npm 网络" >&2
  exit 1
fi

exported=0
for md in "${TARGETS[@]}"; do
  [[ -f "$md" ]] || continue
  base="$(basename "$md" .md)"

  # 提取该 md 内所有 ```mermaid 块；第 1 块命名为 <base>.mmd，后续为 <base>-<i>.mmd
  awk -v base="$base" -v tmp="$TMP_DIR" '
    /^```mermaid/ { in_mermaid=1; i++; file=tmp "/" base (i==1 ? ".mmd" : "-" i ".mmd"); next }
    in_mermaid && /^```$/ { in_mermaid=0; close(file); next }
    in_mermaid { print > file }
  ' "$md"

  for mmd in "$TMP_DIR/$base"*.mmd; do
    [[ -f "$mmd" ]] || continue
    name="$(basename "$mmd" .mmd)"
    svg="$OUT_DIR/$name.svg"
    npx -y -p @mermaid-js/mermaid-cli mmdc -p "$PP_CONFIG" -i "$mmd" -o "$svg" >/dev/null 2>&1
    echo "导出: $svg"
    exported=$((exported + 1))
  done

  # 清理本次提取的临时 .mmd
  rm -f "$TMP_DIR/$base"*.mmd
done

echo "完成，共 $exported 张图"
