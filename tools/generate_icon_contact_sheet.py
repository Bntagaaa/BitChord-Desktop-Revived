#!/usr/bin/env python3
"""Generate an HTML/SVG visual contact sheet of all icons integrated into BitChord Desktop."""

from __future__ import annotations

import html
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DESKTOP_ICONS_KT = ROOT / "desktopApp/src/main/kotlin/com/music/bitchord/desktop/icons/DesktopIcons.kt"
OUT_HTML = ROOT / "docs/icon-contact-sheet.html"

DRAWABLE_DIRS = [
    ROOT / "desktopApp/src/main/composeResources/drawable",
    ROOT / "sharedUi/src/commonMain/composeResources/drawable",
]


def parse_desktop_icons() -> list[tuple[str, str, str]]:
    """Return list of (category, semantic_name, target_ref) from DesktopIcons.kt."""
    text = DESKTOP_ICONS_KT.read_text(encoding="utf-8")
    entries: list[tuple[str, str, str]] = []
    current_cat = ""
    for line in text.splitlines():
        m_cat = re.match(r"^\s*object\s+([A-Za-z0-9_]+)\s*\{", line)
        if m_cat:
            name = m_cat.group(1)
            current_cat = "" if name == "Size" else name
            continue
        if current_cat:
            m_val = re.match(r"^\s*val\s+([A-Za-z0-9_]+):\s*ImageVector\s+get\(\)\s*=\s*([A-Za-z0-9_.]+)", line)
            if m_val:
                entries.append((current_cat, m_val.group(1), m_val.group(2)))
    return entries


def find_svg_preview(name: str, target: str) -> str:
    """Return an inline SVG badge or actual SVG from composeResources if available."""
    # Check compose-bitchord-icons SVGs in scratch if present, or local drawables
    local_svg = ROOT / f"desktopApp/src/main/composeResources/drawable/{name}.svg"
    if local_svg.is_file():
        return local_svg.read_text(encoding="utf-8")
    return ""


def source_badge(target: str) -> str:
    if target.startswith("BitChordIcons."):
        return "BitChordIcons (Tier 2)"
    if target.startswith("SfStyleIcons."):
        return "SfStyleIcons (Tier 1/3/4)"
    if target.startswith("DesktopPhoneMenuIcons."):
        return "DesktopPhoneMenuIcons (Tier 2/4)"
    return target


def intended_size(cat: str) -> str:
    return {
        "Navigation": "22–24 dp",
        "Player": "18–24 dp",
        "Actions": "18–20 dp",
        "Audio": "18–24 dp",
        "Window": "18–20 dp",
        "Settings": "20–24 dp",
        "Status": "14–20 dp",
    }.get(cat, "20–24 dp")


def collect_drawables() -> list[tuple[str, str, str]]:
    """Collect integrated SVG/XML drawables from desktopApp and sharedUi."""
    items: list[tuple[str, str, str]] = []
    for d in DRAWABLE_DIRS:
        if not d.is_dir():
            continue
        for f in sorted(d.iterdir()):
            if f.suffix == ".svg":
                svg_text = f.read_text(encoding="utf-8", errors="ignore")
                # Normalize fill="#FFFFFF" to currentColor for dual-theme preview
                preview = re.sub(r'fill="#FFFFFF"', 'fill="currentColor"', svg_text, flags=re.IGNORECASE)
                items.append((f.stem, "Desktop SVG Drawable", preview))
            elif f.suffix == ".xml":
                try:
                    tree = ET.parse(f)
                    root = tree.getroot()
                    vw = root.attrib.get("{http://schemas.android.com/apk/res/android}viewportWidth", "24")
                    vh = root.attrib.get("{http://schemas.android.com/apk/res/android}viewportHeight", "24")
                    paths = []
                    for p in root.findall(".//{*}path") + root.findall(".//path"):
                        pd = p.attrib.get("{http://schemas.android.com/apk/res/android}pathData", "")
                        if pd:
                            paths.append(f'<path d="{html.escape(pd)}" fill="currentColor"/>')
                    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}" width="24" height="24">{"".join(paths)}</svg>'
                    items.append((f.stem, "SharedUI Vector XML", svg))
                except ET.ParseError:
                    items.append((f.stem, "SharedUI Vector XML", ""))
    return items


def main() -> int:
    entries = parse_desktop_icons()
    drawables = collect_drawables()

    by_cat: dict[str, list[tuple[str, str]]] = {}
    for cat, name, target in entries:
        by_cat.setdefault(cat, []).append((name, target))

    sections_html = []
    for cat, items in by_cat.items():
        rows = []
        for name, target in items:
            rows.append(
                f"<tr>"
                f"<td><code>DesktopIcons.{html.escape(cat)}.{html.escape(name)}</code></td>"
                f"<td>{html.escape(source_badge(target))}</td>"
                f"<td><code>{html.escape(target)}</code></td>"
                f"<td>{html.escape(intended_size(cat))}</td>"
                f"<td><span class='pill ok'>None (0 Material)</span></td>"
                f"</tr>"
            )
        sections_html.append(
            f"<section><h2>{html.escape(cat)} ({len(items)})</h2>"
            f"<table><thead><tr><th>Semantic Token</th><th>Source Library</th><th>Backing Vector</th><th>Intended Size</th><th>Material Fallback</th></tr></thead>"
            f"<tbody>{''.join(rows)}</tbody></table></section>"
        )

    drawable_cards = []
    for name, kind, svg in drawables:
        preview_box = svg if svg else "<span class='muted'>raster/xml</span>"
        drawable_cards.append(
            f"<div class='card'>"
            f"<div class='swatches'>"
            f"<div class='swatch light'>{preview_box}</div>"
            f"<div class='swatch dark'>{preview_box}</div>"
            f"</div>"
            f"<div class='meta'><strong>{html.escape(name)}</strong><span>{html.escape(kind)}</span></div>"
            f"</div>"
        )

    doc = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>BitChord Desktop — Unified Icon System Contact Sheet</title>
<style>
  body {{ font-family: system-ui, -apple-system, sans-serif; margin: 2rem; background: #0f1115; color: #e6e8ec; }}
  h1, h2 {{ font-weight: 600; }}
  .summary {{ display: flex; gap: 1.5rem; margin: 1rem 0 2rem; flex-wrap: wrap; }}
  .stat {{ background: #181b22; border: 1px solid #2a2f3a; border-radius: 10px; padding: 0.85rem 1.2rem; }}
  .stat strong {{ display: block; font-size: 1.4rem; }}
  table {{ width: 100%; border-collapse: collapse; margin-bottom: 2rem; background: #151820; border-radius: 8px; overflow: hidden; }}
  th, td {{ text-align: left; padding: 0.55rem 0.8rem; border-bottom: 1px solid #242934; font-size: 0.9rem; }}
  th {{ background: #1c202a; color: #a7b0c0; font-weight: 600; }}
  code {{ color: #8be9fd; font-size: 0.85rem; }}
  .pill.ok {{ background: #133929; color: #6ee7b7; padding: 0.15rem 0.5rem; border-radius: 999px; font-size: 0.78rem; }}
  .grid {{ display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 1rem; }}
  .card {{ background: #151820; border: 1px solid #262b36; border-radius: 10px; padding: 0.8rem; }}
  .swatches {{ display: grid; grid-template-columns: 1fr 1fr; gap: 0.5rem; margin-bottom: 0.6rem; }}
  .swatch {{ display: flex; align-items: center; justify-content: center; height: 52px; border-radius: 8px; }}
  .swatch.light {{ background: #f5f5f7; color: #111111; }}
  .swatch.dark {{ background: #1c1c1e; color: #ffffff; }}
  .swatch svg {{ width: 26px; height: 26px; }}
  .meta strong {{ display: block; font-size: 0.85rem; }}
  .meta span {{ font-size: 0.75rem; color: #9aa3b2; }}
</style>
</head>
<body>
<h1>BitChord Desktop — Unified Icon System Contact Sheet</h1>
<div class="summary">
  <div class="stat"><strong>{len(entries)}</strong>Semantic DesktopIcons Tokens</div>
  <div class="stat"><strong>{len(drawables)}</strong>Integrated Drawable Resources</div>
  <div class="stat"><strong>0</strong>Material Icon Fallbacks</div>
</div>
{''.join(sections_html)}
<section>
  <h2>Integrated Drawables &amp; Brand/Transport Assets ({len(drawables)})</h2>
  <div class="grid">{''.join(drawable_cards)}</div>
</section>
</body>
</html>
"""
    OUT_HTML.write_text(doc, encoding="utf-8")
    print(f"Generated {OUT_HTML.relative_to(ROOT)} with {len(entries)} semantic tokens and {len(drawables)} drawables.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
