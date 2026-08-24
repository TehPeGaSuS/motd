#!/usr/bin/env python3
"""Generate channel-devicons metadata and lazy path resources from a pinned devicon checkout.

The channel-badge renderer matches IRC channel tokens against icon names, so the
catalog is generated straight from devicon's own metadata (name + altnames)
instead of hand-curating alias sets per icon. Marks devicon cannot supply
(Guix custom art, icons whose plain SVG fails validation below) stay in the
hand-written ChannelDevicon enum in ChannelDeviconBadge.kt.

The compact metadata index is parsed on first channel lookup. Each mark's path data
lives in its own resource and is decoded only when that mark is rendered.

Regenerate:
  curl -sL https://github.com/devicons/devicon/archive/refs/tags/v2.16.0.tar.gz | tar xz -C /tmp
  nix shell nixpkgs#python3 -c python3 tools/gen-channel-devicons/generate.py /tmp/devicon-2.16.0

Only monochrome `<name>-plain.svg` variants consisting purely of <path> elements
(no transforms, no shape primitives, no gradients) are accepted, and icons whose
path data exceeds MAX_PATH_BYTES are skipped to bound APK size. The devicon
project is MIT licensed; THIRD_PARTY_NOTICES.md carries the attribution.
"""

import json
import os
import re
import sys

PIN = "v2.16.0"
MAX_PATH_BYTES = 12_000

# Community aliases devicon's altnames don't carry. Aliases must be lowercase
# [a-z0-9]+ to be reachable by the channel tokenizer.
FIXUPS = {
    "android": ["droid", "aosp"],
    "c": ["clang"],
    "debian": ["deb"],
    "denojs": ["deno"],
    "docker": ["moby"],
    "elixir": ["beam"],
    "emacs": ["emacsen", "spacemacs", "doomemacs"],
    "github": ["octocat", "gh"],
    "go": ["gopher"],
    "haskell": ["ghc", "cabal"],
    "java": ["openjdk"],
    "kubernetes": ["kubectl", "kube", "k8s"],
    "linux": ["tux", "kernel"],
    "neovim": ["nvim"],
    "nixos": ["nixpkgs", "nix"],
    "nodejs": ["node"],
    "postgresql": ["postgres", "psql"],
    "python": ["cpython", "py"],
    "rust": ["rustacean", "cargo"],
    "tor": ["torproject", "onion"],
    "vim": ["vimscript"],
}

# Icon names whose match would be wrong or misleading for ordinary channels.
DENYLIST: set[str] = set()

FORBIDDEN = re.compile(
    r"<(circle|rect|ellipse|polygon|polyline|line|use|mask|clipPath|"
    r"linearGradient|radialGradient|image|text)\b"
)
ALIAS_OK = re.compile(r"^[a-z0-9]+$")


def extract(svg: str):
    """Return (width, height, [(even_odd, path_data)]) or None if unusable."""
    if "transform=" in svg or FORBIDDEN.search(svg):
        return None
    vb = re.search(r'viewBox="([\d.\s-]+)"', svg)
    if not vb:
        return None
    parts = vb.group(1).split()
    if len(parts) != 4 or parts[0] != "0" or parts[1] != "0":
        return None
    paths = []
    for element in re.findall(r"<path\b[^>]*>", svg):
        d = re.search(r'\bd="([^"]+)"', element)
        if not d:
            return None
        even_odd = 'fill-rule="evenodd"' in element
        paths.append((even_odd, d.group(1)))
    if not paths:
        return None
    return float(parts[2]), float(parts[3]), paths


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    root = sys.argv[1]
    out_path = (
        sys.argv[2]
        if len(sys.argv) > 2
        else os.path.join(
            os.path.dirname(__file__),
            "../../app/src/main/resources/channel-devicons-index.json",
        )
    )
    icons = json.load(open(os.path.join(root, "devicon.json")))
    marks, skipped_size, skipped_shape = [], [], []
    for icon in sorted(icons, key=lambda i: i["name"]):
        name = icon["name"]
        if name in DENYLIST:
            continue
        svg_path = os.path.join(root, "icons", name, f"{name}-plain.svg")
        if not os.path.exists(svg_path):
            continue
        extracted = extract(open(svg_path).read())
        if extracted is None:
            skipped_shape.append(name)
            continue
        width, height, paths = extracted
        if sum(len(d) for _, d in paths) > MAX_PATH_BYTES:
            skipped_size.append(name)
            continue
        aliases = [name] + list(icon.get("altnames", [])) + FIXUPS.get(name, [])
        aliases = sorted({a.lower() for a in aliases if ALIAS_OK.match(a.lower())})
        marks.append((name, aliases, width, height, paths))

    payload = [
        {"name": name, "aliases": aliases, "w": width, "h": height}
        for name, aliases, width, height, _ in marks
    ]
    paths_dir = os.path.join(os.path.dirname(out_path), "channel-devicons")
    os.makedirs(paths_dir, exist_ok=True)
    for filename in os.listdir(paths_dir):
        if filename.endswith(".json"):
            os.unlink(os.path.join(paths_dir, filename))
    for name, _, _, _, paths in marks:
        with open(os.path.join(paths_dir, f"{name}.json"), "w") as out:
            json.dump(
                [{"evenOdd": even_odd, "d": d} for even_odd, d in paths],
                out,
                separators=(",", ":"),
                ensure_ascii=False,
            )
            out.write("\n")

    with open(out_path, "w") as out:
        json.dump(payload, out, separators=(",", ":"), ensure_ascii=False)
        out.write("\n")
    total = sum(len(d) for *_, paths in marks for _, d in paths)
    print(f"wrote {len(marks)} marks ({total} path bytes) to {out_path} and {paths_dir}")
    print(f"skipped for size (> {MAX_PATH_BYTES}): {', '.join(skipped_size)}")
    print(f"skipped for shape/validation: {', '.join(skipped_shape)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
