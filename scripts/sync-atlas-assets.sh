#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="${1:-$ROOT_DIR/../exports/worldmap_atlas}"
DST_DIR="$ROOT_DIR/app/src/main/assets/worldmap_atlas"

if [[ ! -d "$SRC_DIR" ]]; then
  echo "[sync-atlas] Source atlas dir not found: $SRC_DIR" >&2
  echo "[sync-atlas] Build atlas first from TurtleWoW root: python3 tools/build_worldmap_atlas.py" >&2
  exit 1
fi

mkdir -p "$DST_DIR"

if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete --exclude '*_debug.png' "$SRC_DIR/" "$DST_DIR/"
else
  rm -rf "$DST_DIR"
  mkdir -p "$DST_DIR"
  cp -R "$SRC_DIR/." "$DST_DIR/"
  find "$DST_DIR" -type f -name '*_debug.png' -delete
fi

count=$(find "$DST_DIR" -type f -name '*_atlas_rowmajor.png' | wc -l | tr -d ' ')
size=$(du -sh "$DST_DIR" | awk '{print $1}')

echo "[sync-atlas] Synced atlas assets: count=$count size=$size"
echo "[sync-atlas] Destination: $DST_DIR"
