#!/bin/bash
# One-time conversion of the desktop app's "calico" theme APNGs into animated
# WebP for Android (no platform APNG decoder exists; ImageDecoder/Coil can
# play animated WebP instead). Not part of the Gradle build — the source
# APNGs don't change, so this is a developer-run-once script, not a build
# step. Requires: ffmpeg, ffprobe, img2webp, webpmux (brew install ffmpeg webp).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC_DIR="$REPO_ROOT/app/themes/calico/assets"
OUT_DIR="$REPO_ROOT/android/app/src/main/res/drawable-nodpi"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

mkdir -p "$OUT_DIR"

# source_file -> output resource name (must be valid Android resource id: lowercase snake_case)
STATES=(
  "calico-idle.apng:calico_idle"
  "calico-thinking.apng:calico_thinking"
  "calico-working-typing.apng:calico_working_typing"
  "calico-happy.apng:calico_happy"
  "calico-error.apng:calico_error"
)

for entry in "${STATES[@]}"; do
  src_name="${entry%%:*}"
  out_name="${entry##*:}"
  src_path="$SRC_DIR/$src_name"
  frame_dir="$WORK_DIR/$out_name"
  mkdir -p "$frame_dir"

  echo "== $src_name -> $out_name.webp =="

  if [[ ! -f "$src_path" ]]; then
    echo "  MISSING SOURCE: $src_path" >&2
    exit 1
  fi

  # Extract frames, alpha preserved (ffmpeg's PNG encoder keeps source RGBA untouched).
  ffmpeg -y -v error -i "$src_path" -vsync 0 "$frame_dir/frame_%04d.png"

  # Per-frame delay in ms (APNG frames can have variable fcTL delays).
  # (Not using `mapfile` — macOS ships bash 3.2, which lacks it.)
  delays_sec=()
  while IFS= read -r line; do
    delays_sec+=("$line")
  done < <(ffprobe -v error -select_streams v:0 \
    -show_entries frame=duration_time -of csv=p=0 "$src_path")

  frames=("$frame_dir"/frame_*.png)
  if [[ ${#frames[@]} -ne ${#delays_sec[@]} ]]; then
    echo "  WARNING: frame count (${#frames[@]}) != delay count (${#delays_sec[@]}), using last known delay for any extras" >&2
  fi

  # Build the img2webp argument list: -d <ms> <frame> for each frame in order.
  # Deliberately using img2webp's default -lossless (not -lossy): tested both
  # on this asset set and -lossy came out LARGER (2.4MB vs 1.6MB total) since
  # these are flat-color cartoon frames, which palette/entropy-coded lossless
  # WebP handles better than lossy VP8's YUV+DCT pipeline. -near_lossless
  # also tested and made zero difference (content has nothing lossy-preproc
  # can exploit) so it's skipped too. -q/-m below still matter in lossless
  # mode (compression effort/ratio), unlike -lossy which doesn't apply here.
  args=(-loop 0 -q 80 -m 6)
  last_delay_ms=100
  for i in "${!frames[@]}"; do
    if [[ -n "${delays_sec[$i]:-}" ]]; then
      # seconds -> integer milliseconds
      last_delay_ms=$(awk -v s="${delays_sec[$i]}" 'BEGIN{printf "%d", (s*1000)+0.5}')
    fi
    args+=(-d "$last_delay_ms" "${frames[$i]}")
  done
  args+=(-o "$OUT_DIR/$out_name.webp")

  img2webp "${args[@]}"

  webpmux -info "$OUT_DIR/$out_name.webp" | grep -E "Canvas size|Number of frames"
  ls -la "$OUT_DIR/$out_name.webp"
  echo
done

echo "Done. Total size:"
du -ch "$OUT_DIR"/calico_*.webp | tail -1
