#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="${1:-$ROOT_DIR/../exports/worldmap_png_all}"
DST_DIR="$ROOT_DIR/app/src/main/assets/worldmap_tiles"

if [[ ! -d "$SRC_DIR" ]]; then
  echo "[sync-tiles] Source tiles dir not found: $SRC_DIR" >&2
  exit 1
fi

mkdir -p "$DST_DIR"

if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete --include '*/' --include '*.png' --exclude '*' "$SRC_DIR/" "$DST_DIR/"
else
  rm -rf "$DST_DIR"
  mkdir -p "$DST_DIR"
  cp -R "$SRC_DIR/." "$DST_DIR/"
  find "$DST_DIR" -type f ! -name '*.png' -delete
fi

zone_count=$(find "$DST_DIR" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')
tile_count=$(find "$DST_DIR" -type f -name '*.png' | wc -l | tr -d ' ')
size=$(du -sh "$DST_DIR" | awk '{print $1}')

echo "[sync-tiles] Synced tiles assets: zones=$zone_count files=$tile_count size=$size"
echo "[sync-tiles] Destination: $DST_DIR"
