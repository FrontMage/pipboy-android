#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_JAVA17="/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home"

SDK_DIR_OVERRIDE=""
HTTP_PROXY_OPT=""
GRADLE_USER_HOME_OPT=""
RESET_GRADLE=0
DO_CLEAN=0
SKIP_ASSET_SYNC=0

usage() {
  cat <<'USAGE'
Build PipBoy Android debug APK with sane defaults.

Usage:
  scripts/build-apk.sh [options]

Options:
  --sdk-dir <path>           Android SDK path (overrides auto-detect)
  --http-proxy <url>         Proxy, e.g. http://192.168.0.102:8080
  --gradle-user-home <path>  Gradle cache dir (default: <project>/.gradle-local)
  --reset-gradle             Remove native/wrapper cache under GRADLE_USER_HOME
  --clean                    Run :app:clean before assemble
  --skip-asset-sync          Skip copying atlas/tiles into app/src/main/assets
  -h, --help                 Show this help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sdk-dir)
      SDK_DIR_OVERRIDE="${2:-}"
      shift 2
      ;;
    --http-proxy)
      HTTP_PROXY_OPT="${2:-}"
      shift 2
      ;;
    --gradle-user-home)
      GRADLE_USER_HOME_OPT="${2:-}"
      shift 2
      ;;
    --reset-gradle)
      RESET_GRADLE=1
      shift
      ;;
    --clean)
      DO_CLEAN=1
      shift
      ;;
    --skip-asset-sync)
      SKIP_ASSET_SYNC=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[build-apk] Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

find_sdk_dir() {
  local cands=()
  if [[ -n "$SDK_DIR_OVERRIDE" ]]; then
    cands+=("$SDK_DIR_OVERRIDE")
  fi
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    cands+=("$ANDROID_HOME")
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    cands+=("$ANDROID_SDK_ROOT")
  fi
  cands+=("$HOME/Library/Android/sdk" "$HOME/Android/Sdk")

  local d
  for d in "${cands[@]}"; do
    [[ -z "$d" ]] && continue
    if [[ -d "$d/platforms" && -d "$d/build-tools" ]]; then
      echo "$d"
      return 0
    fi
  done
  return 1
}

java_major_version() {
  local home="$1"
  [[ -x "$home/bin/java" ]] || return 1
  "$home/bin/java" -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/'
}

if [[ -n "${JAVA_HOME:-}" ]]; then
  major="$(java_major_version "$JAVA_HOME" || true)"
  if [[ "$major" != "17" ]]; then
    echo "[build-apk] JAVA_HOME=$JAVA_HOME (major=$major), switching to JDK 17."
    unset JAVA_HOME
  fi
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d "$DEFAULT_JAVA17" ]]; then
    export JAVA_HOME="$DEFAULT_JAVA17"
  elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "[build-apk] JDK 17 not found. Please set JAVA_HOME to a valid JDK 17." >&2
  exit 1
fi

SDK_DIR="$(find_sdk_dir || true)"
if [[ -z "$SDK_DIR" ]]; then
  cat >&2 <<EOF
[build-apk] Android SDK not found.
Set one of:
  - ANDROID_HOME
  - ANDROID_SDK_ROOT
or pass:
  scripts/build-apk.sh --sdk-dir /path/to/Android/sdk
EOF
  exit 1
fi

LOCAL_PROPERTIES="$ROOT_DIR/local.properties"
ESCAPED_SDK_DIR="$(printf '%s' "$SDK_DIR" | sed 's/\\/\\\\/g')"
printf 'sdk.dir=%s\n' "$ESCAPED_SDK_DIR" > "$LOCAL_PROPERTIES"

if [[ -n "$HTTP_PROXY_OPT" ]]; then
  export http_proxy="$HTTP_PROXY_OPT" https_proxy="$HTTP_PROXY_OPT"
  export HTTP_PROXY="$HTTP_PROXY_OPT" HTTPS_PROXY="$HTTP_PROXY_OPT"
  echo "[build-apk] Using proxy: $HTTP_PROXY_OPT"
fi

if [[ -z "$GRADLE_USER_HOME_OPT" ]]; then
  GRADLE_USER_HOME_OPT="$ROOT_DIR/.gradle-local"
fi
export GRADLE_USER_HOME="$GRADLE_USER_HOME_OPT"
mkdir -p "$GRADLE_USER_HOME"

if [[ "$RESET_GRADLE" -eq 1 ]]; then
  echo "[build-apk] Resetting Gradle caches in $GRADLE_USER_HOME"
  rm -rf "$GRADLE_USER_HOME/native" "$GRADLE_USER_HOME/wrapper/dists"/gradle-* || true
fi

cd "$ROOT_DIR"

if [[ ! -x "./gradlew" ]]; then
  echo "[build-apk] gradlew not found/executable at $ROOT_DIR/gradlew" >&2
  exit 1
fi

echo "[build-apk] JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version
echo "[build-apk] sdk.dir=$SDK_DIR"
echo "[build-apk] GRADLE_USER_HOME=$GRADLE_USER_HOME"

if [[ "$SKIP_ASSET_SYNC" -eq 0 ]]; then
  echo "[build-apk] Syncing atlas assets into APK resources ..."
  "$ROOT_DIR/scripts/sync-atlas-assets.sh"
  echo "[build-apk] Syncing worldmap tiles into APK resources ..."
  "$ROOT_DIR/scripts/sync-tiles-assets.sh"
fi

if [[ "$DO_CLEAN" -eq 1 ]]; then
  ./gradlew --no-daemon :app:clean
fi

./gradlew --no-daemon :app:assembleDebug

APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK_PATH" ]]; then
  if command -v stat >/dev/null 2>&1; then
    SIZE="$(stat -f%z "$APK_PATH" 2>/dev/null || stat -c%s "$APK_PATH" 2>/dev/null || echo '?')"
    echo "[build-apk] Build succeeded: $APK_PATH (${SIZE} bytes)"
  else
    echo "[build-apk] Build succeeded: $APK_PATH"
  fi
else
  echo "[build-apk] Build completed but APK not found: $APK_PATH" >&2
  exit 1
fi
