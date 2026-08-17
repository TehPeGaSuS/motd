#!/usr/bin/env bash
# showcase-composite.sh [screenshot-dir] — merge the light/dark showcase
# capture pairs from runbook.sh phase S into the tracked diagonal-split
# screenshots: Ayu Light in the top-left triangle, Ayu Dark in the
# bottom-right. Intermediates are removed on success.
#
# Inputs:  <dir>/{chat-list,chat,file-uploader}-{light,dark}.png
# Outputs: <dir>/{chat-list,chat,file-uploader}.png
#
# Uses ImageMagick 7 (`magick`, in the default Nix dev shell) or falls back to
# ImageMagick 6 (`convert`/`identify`, preinstalled on GitHub runners).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
SCREENSHOT_DIR="${1:-${E2E_SCREENSHOT_DIR:-$REPO/screenshots}}"

if command -v magick >/dev/null 2>&1; then
  im() { magick "$@"; }
  im_identify() { magick identify "$@"; }
elif command -v convert >/dev/null 2>&1; then
  im() { convert "$@"; }
  im_identify() { identify "$@"; }
else
  echo "showcase-composite: ImageMagick not found (run under 'nix develop')" >&2
  exit 1
fi

for name in chat-list chat file-uploader; do
  light="$SCREENSHOT_DIR/$name-light.png"
  dark="$SCREENSHOT_DIR/$name-dark.png"
  out="$SCREENSHOT_DIR/$name.png"
  [ -s "$light" ] || { echo "showcase-composite: missing $light" >&2; exit 1; }
  [ -s "$dark" ] || { echo "showcase-composite: missing $dark" >&2; exit 1; }

  size="$(im_identify -format '%w %h' "$light")"
  dark_size="$(im_identify -format '%w %h' "$dark")"
  if [ "$size" != "$dark_size" ]; then
    echo "showcase-composite: $name light/dark size mismatch ($size vs $dark_size)" >&2
    exit 1
  fi
  w="${size% *}"
  h="${size#* }"

  # Third image acts as a write mask: white keeps the light overlay (top-left
  # triangle), black reveals the dark base. -draw anti-aliases the seam. The
  # mask must be a real file: an inline \( ... \) image list is not treated as
  # a mask by -composite and silently yields the overlay everywhere.
  mask="$(mktemp --suffix=.png)"
  im -size "${w}x${h}" xc:black -fill white -draw "polygon 0,0 ${w},0 0,${h}" "$mask"
  im "$dark" "$light" "$mask" -composite "$out"

  rm -f "$mask" "$light" "$dark"
  echo "showcase-composite: wrote $out"
done
