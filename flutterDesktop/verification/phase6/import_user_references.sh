#!/usr/bin/env bash
# Import the four user-supplied Phase 6 reference PNGs and push them
# to the Phase 6 branch. Run from anywhere, passing the ZIP path.
set -euo pipefail

repo_root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$repo_root"

if [[ "$(git branch --show-current)" != "codex/phase6-player-lyrics" ]]; then
  echo "ERROR: switch to codex/phase6-player-lyrics before importing." >&2
  exit 1
fi
if [[ -n "$(git diff --cached --name-only)" ]]; then
  echo "ERROR: staged changes detected; commit/unstage them first." >&2
  exit 1
fi

archive="${1:-$HOME/Downloads/phase6_visual_references.zip}"
if [[ ! -f "$archive" ]]; then
  echo "ERROR: ZIP not found: $archive" >&2
  echo "Download phase6_visual_references.zip from the ChatGPT conversation." >&2
  exit 1
fi

temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
unzip -j -q "$archive" 'phase6_visual_references/*.png' -d "$temp"

cat > "$temp/SHA256SUMS" <<'CHECKSUMS'
6e2d64dee2cff172ab275a7fa78d880e1965c6c0b5761d8dd0c4e6d555a2d759  01_home_player_comparison.png
e68b769113e06f9f234b2509d78719241a734ea1d775e0e2a3d1a25f14f26fd9  02_home_states_comparison.png
ee33a4a25dd7e58ac8768cb0dd0ab5b6c3e83455f1219ebbf49f4951d36f8ef5  03_account_popup_comparison.png
9a8102c40daa46afa05d36ebaf4a1f407b4a66fa43ac768a194103ff61d7dc01  04_settings_comparison.png
CHECKSUMS

(cd "$temp" && sha256sum -c SHA256SUMS)
dest="flutterDesktop/verification/phase6/user-references"
mkdir -p "$dest"
files=(
  "$dest/01_home_player_comparison.png"
  "$dest/02_home_states_comparison.png"
  "$dest/03_account_popup_comparison.png"
  "$dest/04_settings_comparison.png"
)
for f in "${files[@]}"; do
  cp "$temp/${f##*/}" "$f"
done

git add -- "${files[@]}"
if git diff --cached --quiet -- "${files[@]}"; then
  echo "All four reference PNGs already committed; nothing to upload."
  exit 0
fi

git commit -m "test(phase6): add four user visual comparison references" -- "${files[@]}"
git push origin HEAD:codex/phase6-player-lyrics
echo "Uploaded all four original PNGs to codex/phase6-player-lyrics."
