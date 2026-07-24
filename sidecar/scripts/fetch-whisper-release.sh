#!/usr/bin/env bash
# Download the official whisper.cpp whisper-server binary for this host.
#
# whisper.cpp only publishes prebuilt GitHub Release binaries for Linux
# (Ubuntu x64/arm64) and Windows — unlike llama.cpp, there's no macOS
# asset. On macOS, `brew install whisper-cpp` is the primary path (see
# gateway/whisper_cpp_stt.py's _candidate_binary_paths, which checks PATH
# there); this script is for Linux/Windows dev + packaged-build fetches.
#
# Output:
#   bin/<os>-<arch>/whisper-server
#
# Honors:
#   WHISPER_CPP_RELEASE = v1.9.1 by default
#   TARGET_TRIPLE        = linux-x64 | linux-arm64
#   WHISPER_OUT_DIR       = override output directory

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

cyan()  { printf "\033[36m%s\033[0m\n" "$*"; }
red()   { printf "\033[31m%s\033[0m\n" "$*" >&2; }
green() { printf "\033[32m%s\033[0m\n" "$*"; }
yellow(){ printf "\033[33m%s\033[0m\n" "$*"; }

TAG="${WHISPER_CPP_RELEASE:-v1.9.1}"

if [[ -n "${TARGET_TRIPLE:-}" ]]; then
  TARGET="$TARGET_TRIPLE"
else
  case "$(uname -s)-$(uname -m)" in
    Darwin-*)
      yellow "macOS has no prebuilt whisper.cpp release binary."
      yellow "Run: brew install whisper-cpp"
      exit 0
      ;;
    Linux-x86_64)  TARGET="linux-x64" ;;
    Linux-aarch64) TARGET="linux-arm64" ;;
    *)
      red "Unsupported host: $(uname -s) $(uname -m)."
      exit 1
      ;;
  esac
fi

case "$TARGET" in
  linux-x64)   ASSET="whisper-bin-ubuntu-x64.tar.gz" ;;
  linux-arm64) ASSET="whisper-bin-ubuntu-arm64.tar.gz" ;;
  *)
    red "Unsupported TARGET_TRIPLE=$TARGET (macOS uses brew, see above)"
    exit 1
    ;;
esac

OUT="${WHISPER_OUT_DIR:-$ROOT/bin/$TARGET}"
URL="https://github.com/ggml-org/whisper.cpp/releases/download/${TAG}/${ASSET}"
TMP="$(mktemp -d)"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

cyan "==> Fetch official whisper.cpp ${TAG}: ${ASSET}"
mkdir -p "$OUT"
curl -L --fail --retry 5 --retry-delay 2 -o "$TMP/$ASSET" "$URL"
mkdir -p "$TMP/extract"
tar -xzf "$TMP/$ASSET" -C "$TMP/extract"

SERVER="$(find "$TMP/extract" -type f -name whisper-server | head -1 || true)"
if [[ -z "$SERVER" ]]; then
  red "whisper-server not found in ${ASSET}"
  exit 1
fi

SERVER_DIR="$(dirname "$SERVER")"
cp -R "$SERVER_DIR"/. "$OUT"/
chmod +x "$OUT/whisper-server" || true

green "==> OK -> $OUT/whisper-server"
