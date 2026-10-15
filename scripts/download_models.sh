#!/usr/bin/env bash
#
# Fetches the model assets that are intentionally NOT committed to this repo
# (they total ~247 MB; clothseg.tflite alone is 176 MB and exceeds GitHub's
# 100 MB per-file hard limit).
#
# Usage:
#   ./scripts/download_models.sh
#
# Each model below has a URL variable. If a variable is empty the script tells
# you what to fetch instead of failing silently. You can also just drop the
# files into app/src/main/assets/models/ by hand — the script only verifies them.
#
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/assets/models"

# ---------------------------------------------------------------------------
# Configure your sources here.
# NOTE: these are left blank on purpose — fill in the exact URLs you use.
# ---------------------------------------------------------------------------
# Model sources (export/download the ONNX or TFLite from these repos):
#   segformer_clothes.onnx   -> https://huggingface.co/mattmdjaga/segformer_b2_clothes
#   segformer_b0_q8.onnx     -> https://huggingface.co/Xenova/segformer_b0_clothes
#   clothseg.tflite          -> https://huggingface.co/litert-community/Cloth-Segmentation-U2Net-LiteRT
URL_SEGFORMER_CLOTHES="${URL_SEGFORMER_CLOTHES:-}"   # -> segformer_clothes.onnx
URL_SEGFORMER_B0="${URL_SEGFORMER_B0:-}"             # -> segformer_b0_q8.onnx
URL_CLOTHSEG_TFLITE="${URL_CLOTHSEG_TFLITE:-}"       # -> clothseg.tflite

mkdir -p "$DEST"

# name|expected bytes|url|description
MODELS=(
  "segformer_clothes.onnx|28635315|$URL_SEGFORMER_CLOTHES|SegFormer-B2 clothes parser (ONNX, INT8, 18-class)"
  "segformer_b0_q8.onnx|4384537|$URL_SEGFORMER_B0|SegFormer-B0 clothes parser (ONNX, INT8)"
  "clothseg.tflite|176300680|$URL_CLOTHSEG_TFLITE|Cloth-Segmentation-U2Net-LiteRT (TFLite)"
)

fetch() {
  local name="$1" url="$2"
  if [ -n "$url" ]; then
    echo "--> downloading $name"
    curl -fL --retry 3 -o "$DEST/$name.part" "$url" && mv "$DEST/$name.part" "$DEST/$name"
  else
    echo "--> SKIPPED $name (no URL configured)"
  fi
}

echo "Model destination: $DEST"
echo

for entry in "${MODELS[@]}"; do
  IFS='|' read -r name bytes url desc <<< "$entry"
  if [ -f "$DEST/$name" ]; then
    echo "== $name already present"
  else
    echo "== $name — $desc"
    fetch "$name" "$url"
  fi
done

# ---------------------------------------------------------------------------
# Verification
# ---------------------------------------------------------------------------
echo
echo "Verification:"
missing=0
for entry in "${MODELS[@]}"; do
  IFS='|' read -r name bytes url desc <<< "$entry"
  if [ ! -f "$DEST/$name" ]; then
    printf '  MISSING  %-26s expected %s bytes\n' "$name" "$bytes"
    missing=$((missing + 1))
    continue
  fi
  actual=$(wc -c < "$DEST/$name" | tr -d ' ')
  if [ "$actual" = "$bytes" ]; then
    printf '  OK       %-26s %s bytes\n' "$name" "$actual"
  else
    printf '  MISMATCH %-26s got %s, expected %s bytes\n' "$name" "$actual" "$bytes"
  fi
done

if [ "$missing" -gt 0 ]; then
  cat <<'EOF'

Some models are missing. Either:
  1. Set the URL_* variables at the top of this script, or
  2. Copy the files into app/src/main/assets/models/ yourself.

Without them the app still builds, but the affected engines fail at runtime
(see the engine table in README.md).
EOF
  exit 1
fi

echo
echo "All models present."
