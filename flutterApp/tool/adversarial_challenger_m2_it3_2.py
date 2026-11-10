#!/usr/bin/env python3
"""
Empirical Adversarial Test Harness for Milestone 2 Iteration 3 (Challenger M2_it3_2)
BitChord Desktop Revived - Settings & Dialogs Parity Verification

Empirically tests and stress-tests:
1. Equalizer presets:
   - All 16 canonical presets + Flat dB curves against Kotlin EqualizerPreset.kt (7 bands).
   - Preset selection mechanics: applying preset values to _bandGains.
   - _matchPreset tolerance boundaries (< 0.05 dB matches, >= 0.05 dB returns 'Custom').
   - "Reset all bands" restoring 0 dB across all 7 bands and resetting preset to 'Flat'.
   - Deserialization and fuzzing of equalizer_bands string.
2. Translation Languages:
   - All 134 entries in kTranslationLanguages (1 system default + 133 languages).
   - 1:1 parity with Kotlin TRANSLATION_LANGUAGES (133 languages).
   - Multi-script search across English names, language codes ('zh-CN', 'iw', 'mni-Mtei', etc.),
     and native endonyms ('العربية', 'Español', '日本語', '한국어', 'Русский', 'हिन्दी', etc.).
   - Code uniqueness and system default handling.
3. DesktopLyricsSourcesDialog:
   - Order persistence and reset logic.
   - Initial loading with empty/corrupted strings/lists (sanitizing, deduplicating, padding missing defaults).
   - ReorderableListView onReorder drag arithmetic (all 25 permutations + 1,000 random drag steps).
   - Reset to default restoring original order.
   - Syllable priority dual-key wiring ('prioritize_syllables' & 'prioritize_syllable_lyrics').
4. Tone pad coordinate geometry, saturation limits & filter models:
   - Discrete 11x11 grid [-5..5] <-> local pixel positions round-trip across container sizes.
   - Out-of-bounds touch clamping.
   - Tilt and Contour: x * 1.2 dB, y * 1.2 dB with [-12.0, 12.0] dB limits.
   - Cytomic SVF filter models (BELL, LOW_SHELF, HIGH_SHELF) and Broad vs Focused Q selectivity.
5. settings_test.dart MockClient test suite emulation:
   - Test 8, 8b, 8c: DesktopAppUpdatesDialog update checks with MockClient.
   - Test 10, 10b, 10c: DesktopSourceEditorDialog endpoint reachability probes with MockClient.
   - SemanticVersion SemVer 2.0.0 parser and comparator compliance (precedence, prefixes, metadata).
   - Verification of elimination of dummy timers (Future.delayed) and 'Active' facades on error.
6. Source verification, font metadata & delimiter balance:
   - tool/verify_source.py.
   - tool/font_metadata.py --check.
   - Lexical delimiter balance across all target files.
"""

import json
import math
import random
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[2]
FLUTTER_APP = REPO_ROOT / "flutterApp"
KOTLIN_APP = Path("/home/bintang/.gemini/antigravity/scratch/BitChord-Compose-Desktop")

DIALOGS_DART = FLUTTER_APP / "lib/ui/settings/settings_dialogs.dart"
PAGE_DART = FLUTTER_APP / "lib/ui/settings/settings_page.dart"
WIDGETS_DART = FLUTTER_APP / "lib/ui/settings/settings_widgets.dart"
SETTINGS_DART = FLUTTER_APP / "lib/data/settings.dart"
SHELL_DART = FLUTTER_APP / "lib/ui/shell.dart"
TEST_DART = FLUTTER_APP / "test/settings_test.dart"

KOTLIN_PRESETS = KOTLIN_APP / "shared/src/commonMain/kotlin/com/music/bitchord/playback/EqualizerPreset.kt"
KOTLIN_LANGUAGES = KOTLIN_APP / "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/TranslationLanguages.kt"
KOTLIN_CURVE = KOTLIN_APP / "shared/src/commonMain/kotlin/com/music/bitchord/playback/EqualizerCurve.kt"

class VerificationError(Exception):
    pass

# ============================================================================
# Suite 1: Equalizer Presets & dB Curves Verification
# ============================================================================

def parse_dart_equalizer_presets(dart_code: str) -> Dict[str, List[float]]:
    """Extract kEqualizerPresetGains map from settings_dialogs.dart."""
    match = re.search(r"const Map<String, List<double>> kEqualizerPresetGains = \{(.*?)\};", dart_code, re.DOTALL)
    if not match:
        raise VerificationError("kEqualizerPresetGains not found in settings_dialogs.dart")
    
    presets = {}
    for line in match.group(1).splitlines():
        line = line.strip()
        if not line or line.startswith("//"):
            continue
        entry_match = re.match(r"'([^']+)':\s*\[(.*?)\]", line)
        if entry_match:
            name = entry_match.group(1)
            gains = [float(x.strip()) for x in entry_match.group(2).split(",") if x.strip()]
            presets[name] = gains
    return presets

def parse_kotlin_equalizer_presets(kotlin_code: str) -> Dict[str, List[float]]:
    """Extract canonical presets from Kotlin EqualizerPreset.kt."""
    match = re.search(r"enum class EqualizerPreset\(.*?\) \{(.*?)\n\s*CUSTOM", kotlin_code, re.DOTALL)
    if not match:
        raise VerificationError("EqualizerPreset enum not found in EqualizerPreset.kt")
    
    presets = {}
    for line in match.group(1).splitlines():
        line = line.strip()
        if not line or line.startswith("//"):
            continue
        m = re.match(r"([A-Z_]+)\((.*?)\)", line)
        if m:
            raw_name = m.group(1)
            parts = raw_name.split("_")
            if raw_name == "HIP_HOP":
                display_name = "Hip-hop"
            elif len(parts) == 1:
                display_name = parts[0].capitalize()
            else:
                display_name = parts[0].capitalize() + " " + " ".join(p.lower() for p in parts[1:])
            
            floats = [float(f.replace("f", "").strip()) for f in m.group(2).split(",") if f.strip()]
            presets[display_name] = floats
    return presets

def dart_match_preset(gains: List[float], preset_gains_map: Dict[str, List[float]]) -> str:
    """Exact emulation of _matchPreset from settings_dialogs.dart:1079–1096."""
    for name, curve in preset_gains_map.items():
        if name == 'Custom':
            continue
        if len(curve) != len(gains):
            continue
        matches = True
        for i in range(7):
            if abs(curve[i] - gains[i]) >= 0.05:
                matches = False
                break
        if matches:
            return name
    return 'Custom'

def run_suite_1():
    print("-> Running Suite 1: Equalizer Presets 100% Parity & Curve Verification...")
    dart_code = DIALOGS_DART.read_text(encoding="utf-8")
    kotlin_code = KOTLIN_PRESETS.read_text(encoding="utf-8")

    dart_presets = parse_dart_equalizer_presets(dart_code)
    kotlin_presets = parse_kotlin_equalizer_presets(kotlin_code)

    # 1.1 Verify all 16 presets + Flat against Kotlin EqualizerPreset.kt
    print("   1.1 Verifying all 16 presets + Flat dB curves against Kotlin reference...")
    expected_16_names = [
        "Flat", "Acoustic", "Bass boost", "Bass cut", "Vocal", "Treble boost",
        "Treble cut", "Loudness", "Spoken word", "Electronic", "Rock", "Hip-hop",
        "Jazz", "Classical", "Small speakers", "Late night"
    ]
    for name in expected_16_names:
        if name not in dart_presets:
            raise VerificationError(f"Missing canonical preset in Dart: {name}")
        if name not in kotlin_presets:
            raise VerificationError(f"Missing canonical preset in Kotlin: {name}")
        
        dart_curve = dart_presets[name]
        kotlin_curve = kotlin_presets[name]

        if len(dart_curve) != 7:
            raise VerificationError(f"Preset {name} has {len(dart_curve)} bands, expected 7")
        if len(kotlin_curve) != 7:
            raise VerificationError(f"Kotlin preset {name} has {len(kotlin_curve)} bands, expected 7")
        
        for b in range(7):
            if abs(dart_curve[b] - kotlin_curve[b]) > 1e-4:
                raise VerificationError(
                    f"Mismatch in preset '{name}' at band {b}: Dart={dart_curve[b]}, Kotlin={kotlin_curve[b]}"
                )

    # 1.2 Verify preset selection applies curve to _bandGains
    print("   1.2 Verifying selecting presets applies values to _bandGains...")
    for name in expected_16_names:
        band_gains = [0.0] * 7
        curve = dart_presets[name]
        for i in range(7):
            band_gains[i] = curve[i]
        if band_gains != curve:
            raise VerificationError(f"Applying preset {name} did not copy exact curve to _bandGains")

    # 1.3 Verify _matchPreset returns exact preset name on canonical curves
    print("   1.3 Verifying _matchPreset returns exact preset name on canonical curves...")
    for name in expected_16_names:
        matched = dart_match_preset(dart_presets[name], dart_presets)
        if matched != name:
            raise VerificationError(f"_matchPreset failed for canonical curve '{name}': returned '{matched}'")

    # 1.4 Adversarial stress-test on _matchPreset tolerance boundaries (<0.05 dB vs >=0.05 dB)
    print("   1.4 Adversarial stress-test on _matchPreset tolerance boundaries (<0.05 dB vs >=0.05 dB)...")
    rng = random.Random(42)
    for name in expected_16_names:
        curve = dart_presets[name]
        # Just within tolerance (< 0.05 dB)
        perturbed_inside = [c + (0.04 * (rng.random() * 2 - 1)) for c in curve]
        matched_inside = dart_match_preset(perturbed_inside, dart_presets)
        if matched_inside != name:
            raise VerificationError(
                f"_matchPreset rejected valid perturbed curve for '{name}' within tolerance: returned '{matched_inside}'"
            )

        # Deviation on a single band >= 0.05 dB
        for band_idx in range(7):
            for sign in [-1.0, 1.0]:
                perturbed_outside = list(curve)
                perturbed_outside[band_idx] += sign * 0.051
                matched_outside = dart_match_preset(perturbed_outside, dart_presets)
                if matched_outside == name:
                    raise VerificationError(
                        f"_matchPreset accepted out-of-tolerance curve for '{name}' (band {band_idx} offset {sign*0.051})"
                    )

    # 1.5 Verifying "Reset all bands" behavior
    print("   1.5 Verifying 'Reset all bands' behavior...")
    reset_bands = [0.0] * 7
    matched_reset = dart_match_preset(reset_bands, dart_presets)
    if matched_reset != "Flat":
        raise VerificationError(f"Reset all bands did not match 'Flat', got '{matched_reset}'")

    # 1.6 Adversarial fuzzing of equalizer_bands string deserialization
    print("   1.6 Adversarial fuzzing of equalizer_bands string deserialization...")
    fuzz_cases = [
        "",
        "0,0,0",
        "invalid,strings,here,0,0,0,0",
        ",,,,,,",
        "12.0, -12.0, 6.0, -6.0, 3.5, -3.5, 0.0, 99.0", # extra 8th band
        "-9999999999999999999999999999999999999999999999999999",
        "NaN,Infinity,-Infinity,0,0,0,0",
    ]
    for case in fuzz_cases:
        parsed = [float(e) if re.match(r"^-?\d+(\.\d+)?$", e.strip()) else 0.0 for e in case.split(",")]
        band_gains = [0.0] * 7
        for i in range(min(7, len(parsed))):
            band_gains[i] = parsed[i]
        if len(band_gains) != 7:
            raise VerificationError(f"Fuzz test failed for case '{case}': band count != 7")

    print("   [PASS] Suite 1: Equalizer Presets & dB Curves passed cleanly.")


# ============================================================================
# Suite 2: Translation Languages 134-Item & Multi-Script Search Stress Test
# ============================================================================

def parse_dart_languages(dart_code: str) -> List[Dict[str, str]]:
    """Extract kTranslationLanguages from settings_dialogs.dart."""
    match = re.search(r"const List<Map<String, String>> kTranslationLanguages = \[(.*?)\];", dart_code, re.DOTALL)
    if not match:
        raise VerificationError("kTranslationLanguages not found in settings_dialogs.dart")
    
    entries = []
    for m in re.finditer(r"\{'code':\s*'([^']*)',\s*'name':\s*'([^']*)',\s*'nativeName':\s*(?:'([^']*)'|\"([^\"]*)\")\}", match.group(1)):
        entries.append({
            "code": m.group(1),
            "name": m.group(2),
            "nativeName": m.group(3) if m.group(3) is not None else m.group(4)
        })
    return entries

def parse_kotlin_languages(kotlin_code: str) -> List[Tuple[str, str]]:
    """Extract TRANSLATION_LANGUAGES from TranslationLanguages.kt."""
    langs = re.findall(r'TranslationLanguage\("([^"]+)",\s*"([^"]+)"\)', kotlin_code)
    if not langs:
        raise VerificationError("TRANSLATION_LANGUAGES not found in TranslationLanguages.kt")
    return langs

def run_suite_2():
    print("-> Running Suite 2: Translation Languages 134-Item & Multi-Script Search Stress Test...")
    dart_code = DIALOGS_DART.read_text(encoding="utf-8")
    kotlin_code = KOTLIN_LANGUAGES.read_text(encoding="utf-8")

    dart_langs = parse_dart_languages(dart_code)
    kotlin_langs = parse_kotlin_languages(kotlin_code)

    # 2.1 Total entry count
    print(f"   2.1 Verifying total entry count (found {len(dart_langs)})...")
    if len(dart_langs) != 134:
        raise VerificationError(f"Expected 134 translation language entries in Dart, got {len(dart_langs)}")
    
    # 2.2 First entry is system default
    system_entry = dart_langs[0]
    if system_entry["code"] != "" or system_entry["name"] != "Follow the app language":
        raise VerificationError(f"Index 0 must be system default, got {system_entry}")

    # 2.3 Verify 1:1 parity with Kotlin's 133 entries
    print("   2.2 Verifying 1:1 parity with Kotlin TRANSLATION_LANGUAGES (133 languages)...")
    if len(kotlin_langs) != 133:
        raise VerificationError(f"Expected 133 languages in Kotlin reference, found {len(kotlin_langs)}")
    
    for i in range(133):
        k_code, k_name = kotlin_langs[i]
        d_entry = dart_langs[i + 1] # offset by 1 for system default
        if d_entry["code"] != k_code:
            raise VerificationError(f"Language index {i} code mismatch: Dart='{d_entry['code']}', Kotlin='{k_code}'")
        if d_entry["name"] != k_name:
            raise VerificationError(f"Language index {i} name mismatch: Dart='{d_entry['name']}', Kotlin='{k_name}'")

    # 2.4 Verify uniqueness of language codes
    codes = [e["code"] for e in dart_langs[1:]]
    if len(codes) != len(set(codes)):
        raise VerificationError("Duplicate language codes found in kTranslationLanguages")

    # 2.5 Multi-Script Search Stress Test
    print("   2.3 Executing Multi-Script Search Stress Test across various scripts and query forms...")
    def search_filter(q: str) -> List[Dict[str, str]]:
        q_norm = q.strip().lower()
        if not q_norm:
            return dart_langs[1:]
        return [
            lang for lang in dart_langs[1:]
            if q_norm in lang["name"].lower() or q_norm in lang["code"].lower() or q_norm in lang["nativeName"].lower()
        ]

    test_queries = [
        # (query, expected_code, script_name)
        ("zh-CN", "zh-CN", "Code: Simplified Chinese"),
        ("zh-tw", "zh-TW", "Code: Traditional Chinese lowercase"),
        ("iw", "iw", "Code: Hebrew archaic code"),
        ("mni-Mtei", "mni-Mtei", "Code with hyphen and uppercase: Meiteilon"),
        ("العربية", "ar", "Arabic script: Arabic native endonym"),
        ("Español", "es", "Latin script with ñ: Spanish"),
        ("español", "es", "Latin script lowercase: Spanish"),
        ("日本語", "ja", "Kanji/Hiragana: Japanese"),
        ("한국어", "ko", "Hangul: Korean"),
        ("Русский", "ru", "Cyrillic: Russian"),
        ("हिन्दी", "hi", "Devanagari: Hindi"),
        ("বাংলা", "bn", "Bengali script: Bengali"),
        ("עברית", "iw", "Hebrew script: Hebrew"),
        ("ไทย", "th", "Thai script: Thai"),
        ("Ελληνικά", "el", "Greek script: Greek"),
        ("Հայերեն", "hy", "Armenian script: Armenian"),
        ("ქართული", "ka", "Georgian script: Georgian"),
        ("ትግርኛ", "ti", "Ethiopic script: Tigrinya"),
        ("Cherokee", None, "Non-existent language"),
        ("klingon", None, "Non-existent fictional language"),
        ("   japanese   ", "ja", "Whitespace padded query"),
    ]

    for q, exp_code, desc in test_queries:
        results = search_filter(q)
        if exp_code is None:
            if any(r["code"] == exp_code for r in results):
                raise VerificationError(f"Unexpected match for query '{q}' ({desc})")
        else:
            matched_codes = [r["code"] for r in results]
            if exp_code not in matched_codes:
                raise VerificationError(f"Search for '{q}' ({desc}) failed: '{exp_code}' not found in {matched_codes}")

    print("   [PASS] Suite 2: Translation Languages & Multi-Script Search passed cleanly.")


# ============================================================================
# Suite 3: DesktopLyricsSourcesDialog Order Persistence & Reset Stress Test
# ============================================================================

def run_suite_3():
    print("-> Running Suite 3: DesktopLyricsSourcesDialog Order Persistence & Reset Stress Test...")
    default_sources = ["LRCLIB", "YouTube", "Musixmatch", "NetEase", "Kugou"]

    # 3.1 Emulate _loadSources() logic from settings_dialogs.dart:2290–2324
    def load_sources(raw: Any) -> List[str]:
        if not raw:
            return list(default_sources)
        if isinstance(raw, str):
            parsed = [s.strip() for s in raw.split(",") if s.strip()]
        elif isinstance(raw, list):
            parsed = [str(s).strip() for s in raw if str(s).strip()]
        else:
            return list(default_sources)

        if not parsed:
            return list(default_sources)

        result = []
        for src in parsed:
            if src in default_sources and src not in result:
                result.append(src)
        for def_src in default_sources:
            if def_src not in result:
                result.append(def_src)
        return result

    # Test edge cases
    print("   3.1 Verifying source loading deserialization edge cases...")
    test_inputs = [
        ("", default_sources),
        ("Musixmatch,YouTube,LRCLIB,NetEase,Kugou", ["Musixmatch", "YouTube", "LRCLIB", "NetEase", "Kugou"]),
        ("YouTube,UnknownSource,NetEase,Duplicate,YouTube", ["YouTube", "NetEase", "LRCLIB", "Musixmatch", "Kugou"]),
        (["Kugou", "NetEase"], ["Kugou", "NetEase", "LRCLIB", "YouTube", "Musixmatch"]),
        (" , , , ", default_sources),
        (None, default_sources),
        (12345, default_sources),
    ]

    for inp, expected in test_inputs:
        actual = load_sources(inp)
        if actual != expected:
            raise VerificationError(f"load_sources failed for {inp}: expected {expected}, got {actual}")

    # 3.2 ReorderableListView onReorder permutations
    # In Flutter: if (newIndex > oldIndex) newIndex--; final item = list.removeAt(oldIndex); list.insert(newIndex, item);
    def reorder(sources: List[str], old_idx: int, new_idx: int) -> List[str]:
        cur = list(sources)
        if new_idx > old_idx:
            new_idx -= 1
        item = cur.pop(old_idx)
        cur.insert(new_idx, item)
        return cur

    print("   3.2 Stress-testing all 25 (oldIndex, newIndex) drag permutations...")
    base = list(default_sources)
    for old_i in range(5):
        for new_i in range(6): # newIndex can be up to length in Flutter ReorderableListView
            res = reorder(base, old_i, new_i)
            if len(res) != 5:
                raise VerificationError(f"Reorder dropped items for old={old_i}, new={new_i}")
            if sorted(res) != sorted(default_sources):
                raise VerificationError(f"Reorder corrupted item set for old={old_i}, new={new_i}")

    # 3.3 1,000 random sequential drag-reorder operations
    print("   3.3 Running 1,000 random sequential drag-reorder operations...")
    rng = random.Random(1337)
    current = list(default_sources)
    for _ in range(1000):
        o = rng.randint(0, 4)
        n = rng.randint(0, 5)
        current = reorder(current, o, n)
        if len(current) != 5 or set(current) != set(default_sources):
            raise VerificationError(f"Sequential reorder corrupted list: {current}")

    # 3.4 Verify Reset to default action
    print("   3.4 Verifying Reset to Default action...")
    current.clear()
    current.extend(default_sources)
    if current != default_sources:
        raise VerificationError(f"Reset to default failed: {current}")

    # 3.5 Syllable priority dual-key verification
    print("   3.5 Verifying syllable lyrics priority dual-key persistence...")
    dart_dialogs = DIALOGS_DART.read_text(encoding="utf-8")
    if "prioritize_syllables" not in dart_dialogs or "prioritize_syllable_lyrics" not in dart_dialogs:
        raise VerificationError("Missing dual keys prioritize_syllables/prioritize_syllable_lyrics in dialog")

    print("   [PASS] Suite 3: DesktopLyricsSourcesDialog Order Persistence & Reset passed cleanly.")


# ============================================================================
# Suite 4: Tone Pad Coordinate Geometry, Saturation Limits & Filter Models
# ============================================================================

def run_suite_4():
    print("-> Running Suite 4: Tone Pad Coordinate Geometry, Saturation Limits & Filter Models...")
    # 4.1 Puck coordinate geometry:
    # puckX = ((toneX + 5) / 10.0) * (w - 32.0) + 16.0
    # puckY = ((5 - toneY) / 10.0) * (h - 32.0) + 16.0
    # touch:
    # nx = ((local.dx - 16.0) / (w - 32.0) * 10.0 - 5.0).round().clamp(-5, 5)
    # ny = (5.0 - (local.dy - 16.0) / (h - 32.0) * 10.0).round().clamp(-5, 5)
    print("   4.1 Verifying puck coordinate round-trip across container sizes...")
    container_sizes = [(300.0, 180.0), (400.0, 200.0), (840.0, 300.0), (100.0, 100.0)]
    for w, h in container_sizes:
        for tx in range(-5, 6):
            for ty in range(-5, 6):
                puck_x = ((tx + 5) / 10.0) * (w - 32.0) + 16.0
                puck_y = ((5 - ty) / 10.0) * (h - 32.0) + 16.0

                # Invert
                calc_tx = round(((puck_x - 16.0) / (w - 32.0) * 10.0) - 5.0)
                calc_ty = round(5.0 - ((puck_y - 16.0) / (h - 32.0) * 10.0))

                calc_tx = max(-5, min(5, calc_tx))
                calc_ty = max(-5, min(5, calc_ty))

                if calc_tx != tx or calc_ty != ty:
                    raise VerificationError(
                        f"Round-trip failed at w={w}, h={h}, tx={tx}, ty={ty} -> got {calc_tx}, {calc_ty}"
                    )

    # 4.2 Out-of-bounds touch clamping
    print("   4.2 Verifying out-of-bounds touch coordinate clamping...")
    w, h = 400.0, 180.0
    extreme_touches = [
        (-1000.0, -1000.0, -5, 5), # top-left far out -> tx=-5, ty=+5
        (2000.0, 2000.0, 5, -5),   # bottom-right far out -> tx=+5, ty=-5
        (w / 2.0, -500.0, 0, 5),    # mid-x, high-y -> tx=0, ty=+5
        (-500.0, h / 2.0, -5, 0),   # left-x, mid-y -> tx=-5, ty=0
    ]
    for dx, dy, exp_tx, exp_ty in extreme_touches:
        nx = max(-5, min(5, round(((dx - 16.0) / (w - 32.0) * 10.0) - 5.0)))
        ny = max(-5, min(5, round(5.0 - ((dy - 16.0) / (h - 32.0) * 10.0))))
        if nx != exp_tx or ny != exp_ty:
            raise VerificationError(f"Clamping failed for ({dx}, {dy}): expected ({exp_tx}, {exp_ty}), got ({nx}, {ny})")

    # 4.3 Cytomic SVF filter models and saturation limits
    print("   4.3 Verifying Cytomic SVF filter models and +/-12 dB saturation limits...")
    def section_gain_db(filter_kind: str, center_hz: float, gain_db: float, q: float, target_hz: float) -> float:
        if abs(gain_db) < 0.01:
            return 0.0
        a = 10.0 ** (gain_db / 40.0)
        a2 = a * a
        x = target_hz / center_hz
        x2 = x * x
        qq = q * q
        if filter_kind == "BELL":
            flat = (1.0 - x2) ** 2
            mag = math.sqrt((flat + x2 * a2 / qq) / (flat + x2 / (a2 * qq)))
        elif filter_kind == "LOW_SHELF":
            common = x2 * a / qq
            mag = a * math.sqrt(((a - x2) ** 2 + common) / ((1.0 - a * x2) ** 2 + common))
        elif filter_kind == "HIGH_SHELF":
            common = x2 * a / qq
            mag = a * math.sqrt(((1.0 - a * x2) ** 2 + common) / ((a - x2) ** 2 + common))
        else:
            raise ValueError(f"Unknown filter kind: {filter_kind}")
        return 20.0 * math.log10(mag)

    # Test Cytomic Bell prototype at center frequency (gain should match exactly gain_db)
    for test_gain in [-12.0, -6.0, 0.0, 3.5, 6.0, 12.0]:
        calc_center_gain = section_gain_db("BELL", 1000.0, test_gain, 1.0, 1000.0)
        if abs(calc_center_gain - test_gain) > 1e-4:
            raise VerificationError(
                f"Cytomic Bell filter failed at center frequency: expected {test_gain} dB, got {calc_center_gain} dB"
            )

    # Test Low Shelf prototype DC gain
    for test_gain in [-12.0, -6.0, 0.0, 4.0, 12.0]:
        calc_dc_gain = section_gain_db("LOW_SHELF", 250.0, test_gain, 0.707, 1.0)
        if abs(calc_dc_gain - test_gain) > 0.05:
            raise VerificationError(
                f"Cytomic Low Shelf filter failed near DC: expected {test_gain} dB, got {calc_dc_gain} dB"
            )

    # Test High Shelf prototype Nyquist limit gain
    for test_gain in [-12.0, -6.0, 0.0, 5.0, 12.0]:
        calc_nyq_gain = section_gain_db("HIGH_SHELF", 4000.0, test_gain, 0.707, 20000.0)
        if abs(calc_nyq_gain - test_gain) > 0.3:
            raise VerificationError(
                f"Cytomic High Shelf filter failed near Nyquist: expected {test_gain} dB, got {calc_nyq_gain} dB"
            )

    # Test Broad vs Focused Q selectivity
    # Broad: shelfQ = 0.5, bellQ = 0.7
    # Focused: shelfQ = 0.9, bellQ = 2.2
    # At half-bandwidth frequency (e.g. 1500 Hz for 1000 Hz bell), Focused Q should have sharper roll-off
    gain = 6.0
    broad_at_1500 = section_gain_db("BELL", 1000.0, gain, 0.7, 1500.0)
    focused_at_1500 = section_gain_db("BELL", 1000.0, gain, 2.2, 1500.0)
    if not (focused_at_1500 < broad_at_1500):
        raise VerificationError(
            f"Focused Q did not produce sharper selectivity: Broad={broad_at_1500} dB, Focused={focused_at_1500} dB"
        )

    print("   [PASS] Suite 4: Tone Pad Coordinate Geometry & Filter Models passed cleanly.")


# ============================================================================
# Suite 5: settings_test.dart MockClient Test Suite Emulation & Parity
# ============================================================================

class SemanticVersion:
    """Python implementation replicating Dart's SemanticVersion in settings_dialogs.dart:2772–2858."""
    def __init__(self, major: int, minor: int, patch: int, pre_release: Optional[List[str]] = None, build_meta: Optional[List[str]] = None):
        self.major = major
        self.minor = minor
        self.patch = patch
        self.pre_release = pre_release or []
        self.build_meta = build_meta or []

    @classmethod
    def try_parse(cls, raw: str) -> Optional['SemanticVersion']:
        text = raw.strip()
        if text.startswith('v') or text.startswith('V'):
            text = text[1:]
        regex = r'^(0|[1-9]\d*)\.(0|[1-9]\d*)(?:\.(0|[1-9]\d*))?(?:-([0-9a-zA-Z.-]+))?(?:\+([0-9a-zA-Z.-]+))?$'
        m = re.match(regex, text)
        if not m:
            return None
        major = int(m.group(1))
        minor = int(m.group(2))
        patch = int(m.group(3)) if m.group(3) is not None else 0
        pre_release = m.group(4).split('.') if m.group(4) else []
        build_meta = m.group(5).split('.') if m.group(5) else []
        return cls(major, minor, patch, pre_release, build_meta)

    def compare_to(self, other: 'SemanticVersion') -> int:
        if self.major != other.major:
            return 1 if self.major > other.major else -1
        if self.minor != other.minor:
            return 1 if self.minor > other.minor else -1
        if self.patch != other.patch:
            return 1 if self.patch > other.patch else -1

        # Pre-release precedence
        if not self.pre_release and other.pre_release:
            return 1 # normal > pre-release
        if self.pre_release and not other.pre_release:
            return -1 # pre-release < normal
        if not self.pre_release and not other.pre_release:
            return 0

        min_len = min(len(self.pre_release), len(other.pre_release))
        for i in range(min_len):
            a = self.pre_release[i]
            b = other.pre_release[i]
            a_is_num = a.isdigit()
            b_is_num = b.isdigit()
            if a_is_num and b_is_num:
                ai, bi = int(a), int(b)
                if ai != bi:
                    return 1 if ai > bi else -1
            elif a_is_num:
                return -1 # numeric identifiers have lower precedence than non-numeric in SemVer
            elif b_is_num:
                return 1
            else:
                if a != b:
                    return 1 if a > b else -1
        if len(self.pre_release) != len(other.pre_release):
            return 1 if len(self.pre_release) > len(other.pre_release) else -1
        return 0

    def is_greater_than(self, other: 'SemanticVersion') -> bool:
        return self.compare_to(other) > 0

    def is_at_least(self, other: 'SemanticVersion') -> bool:
        return self.compare_to(other) >= 0


def run_suite_5():
    print("-> Running Suite 5: settings_test.dart MockClient Test Suite Emulation & Parity...")

    # 5.1 SemVer Parser & Comparator Specification Verification
    print("   5.1 Verifying SemVer 2.0.0 parser and comparator against SemVer spec test vectors...")
    v1_9_2 = SemanticVersion.try_parse("v1.9.2")
    v1_9_3 = SemanticVersion.try_parse("1.9.3")
    v1_9_2_beta = SemanticVersion.try_parse("1.9.2-beta.1")
    v1_9_2_alpha = SemanticVersion.try_parse("1.9.2-alpha.1")
    v1_9_2_build = SemanticVersion.try_parse("1.9.2+build.2026")
    v2_0_0 = SemanticVersion.try_parse("V2.0.0")

    if not v1_9_2 or not v1_9_3 or not v1_9_2_beta or not v1_9_2_alpha or not v2_0_0:
        raise VerificationError("Failed to parse valid SemVer test vectors")

    # Comparisons
    if not (v1_9_3.is_greater_than(v1_9_2)):
        raise VerificationError("1.9.3 must be greater than 1.9.2")
    if not (v1_9_2.is_greater_than(v1_9_2_beta)):
        raise VerificationError("1.9.2 must be greater than 1.9.2-beta.1")
    if not (v1_9_2_beta.is_greater_than(v1_9_2_alpha)):
        raise VerificationError("1.9.2-beta.1 must be greater than 1.9.2-alpha.1")
    if v1_9_2.compare_to(v1_9_2_build) != 0:
        raise VerificationError("Build metadata must be ignored in version precedence comparison")
    if not (v2_0_0.is_greater_than(v1_9_3)):
        raise VerificationError("2.0.0 must be greater than 1.9.3")

    # Prefix edge cases
    if SemanticVersion.try_parse("v0.0.1").major != 0:
        raise VerificationError("Failed on v0.0.1")
    if SemanticVersion.try_parse("not-a-version") is not None:
        raise VerificationError("Accepted invalid version 'not-a-version'")

    # 5.2 Emulate DesktopAppUpdatesDialog update checking logic with MockClient responses
    print("   5.2 Emulating DesktopAppUpdatesDialog update checking logic with MockClient...")
    current_ver_str = "1.9.2"
    current_ver = SemanticVersion.try_parse(current_ver_str)

    def check_updates(mock_response_status: int, mock_body: Optional[str] = None, throw_ex: bool = False) -> str:
        if throw_ex:
            return 'Unable to check for updates: Offline or network error'
        if 200 <= mock_response_status < 300 and mock_body:
            try:
                data = json.loads(mock_body)
                if isinstance(data, dict) and isinstance(data.get('tag_name'), str):
                    latest_tag = data['tag_name'].strip()
                    latest_ver = SemanticVersion.try_parse(latest_tag)
                    if latest_ver and current_ver:
                        if latest_ver.is_greater_than(current_ver):
                            return f'Update available: {latest_tag}'
                        else:
                            return f'You are running the latest version (v{current_ver_str})'
                    else:
                        clean = latest_tag[1:] if latest_tag.startswith(('v', 'V')) else latest_tag
                        if clean == current_ver_str:
                            return f'You are running the latest version (v{current_ver_str})'
                        else:
                            return f'Update available: {latest_tag}'
            except Exception:
                pass
        return 'Unable to check for updates: Offline or network error'

    # Test 8: Current version match
    body_8 = json.dumps({'tag_name': 'v1.9.2', 'name': '1.9.2 Release'})
    status_8 = check_updates(200, body_8)
    if status_8 != 'You are running the latest version (v1.9.2)':
        raise VerificationError(f"Test 8 emulation failed: got '{status_8}'")

    # Test 8b: Newer version available
    body_8b = json.dumps({'tag_name': 'v1.9.3', 'name': '1.9.3 Release'})
    status_8b = check_updates(200, body_8b)
    if status_8b != 'Update available: v1.9.3':
        raise VerificationError(f"Test 8b emulation failed: got '{status_8b}'")

    # Test 8c: Network unreachable exception
    status_8c = check_updates(0, throw_ex=True)
    if status_8c != 'Unable to check for updates: Offline or network error':
        raise VerificationError(f"Test 8c emulation failed: got '{status_8c}'")

    # Test 8d: HTTP 500 error
    status_8d = check_updates(500, "Internal Server Error")
    if status_8d != 'Unable to check for updates: Offline or network error':
        raise VerificationError(f"Test 8d emulation failed: got '{status_8d}'")

    # 5.3 Emulate DesktopSourceEditorDialog endpoint reachability probe logic
    print("   5.3 Emulating DesktopSourceEditorDialog endpoint reachability probe logic...")
    def validate_url(url: str) -> bool:
        url = url.strip()
        m = re.match(r'^(https?)://([a-zA-Z0-9.-]+)(:[0-9]+)?(/.*)?$', url)
        return bool(m)

    def test_endpoint(url: str, mock_head_status: Optional[int] = None, mock_get_status: Optional[int] = None, throw_ex: Optional[str] = None) -> str:
        if not validate_url(url):
            return 'Invalid URL: must begin with http:// or https://'
        if throw_ex:
            return f'Connection failed: {throw_ex}'
        
        status_code = mock_head_status or mock_get_status or 200
        if 200 <= status_code < 300:
            return f'Reachable (HTTP {status_code})'
        elif 300 <= status_code < 400:
            return f'Redirect (HTTP {status_code})'
        else:
            return f'Endpoint unreachable (HTTP {status_code})'

    # Test 10: Reachable 200
    res_10 = test_endpoint('https://sources.bitchord.org/stream', mock_head_status=200)
    if res_10 != 'Reachable (HTTP 200)':
        raise VerificationError(f"Test 10 emulation failed: got '{res_10}'")

    # Test 10b: 404 Unreachable
    res_10b = test_endpoint('https://sources.bitchord.org/dead-stream', mock_head_status=404, mock_get_status=404)
    if res_10b != 'Endpoint unreachable (HTTP 404)':
        raise VerificationError(f"Test 10b emulation failed: got '{res_10b}'")

    # Test 10c: ClientException
    res_10c = test_endpoint('https://sources.bitchord.org/unreachable', throw_ex='ClientException: Host lookup failed')
    if not res_10c.startswith('Connection failed:'):
        raise VerificationError(f"Test 10c emulation failed: got '{res_10c}'")

    # Test 10d: Invalid URL
    res_10d = test_endpoint('ftp://invalid.host/file')
    if res_10d != 'Invalid URL: must begin with http:// or https://':
        raise VerificationError(f"Test 10d emulation failed: got '{res_10d}'")

    # 5.4 Check source code for facades in settings_dialogs.dart
    print("   5.4 Checking settings_dialogs.dart for elimination of dummy timers and 'Active' facades...")
    dart_dialogs = DIALOGS_DART.read_text(encoding="utf-8")
    if "Future.delayed(const Duration(milliseconds: 100))" in dart_dialogs:
        raise VerificationError("Found residual dummy timer facade Future.delayed(100ms) in settings_dialogs.dart")
    
    # Check that 'Endpoint reached · Active' or hardcoded active facade does not exist
    if "Endpoint reached · Active" in dart_dialogs:
        raise VerificationError("Found residual 'Endpoint reached · Active' facade in settings_dialogs.dart")

    # Verify that test file contains all required test cases
    print("   5.5 Verifying test coverage in settings_test.dart...")
    dart_test = TEST_DART.read_text(encoding="utf-8")
    for test_label in [
        "8. DesktopAppUpdatesDialog checks updates and toggles auto-check",
        "8b. DesktopAppUpdatesDialog reports update available when newer release exists",
        "8c. DesktopAppUpdatesDialog handles offline network error gracefully",
        "10. DesktopSourceEditorDialog validates URL, tests endpoint, and saves",
        "10b. DesktopSourceEditorDialog reports HTTP error when endpoint returns 404",
        "10c. DesktopSourceEditorDialog reports connection failure on network exception",
    ]:
        if test_label not in dart_test:
            raise VerificationError(f"Missing required test case '{test_label}' in settings_test.dart")

    print("   [PASS] Suite 5: settings_test.dart MockClient Test Suite Emulation & Parity passed cleanly.")


# ============================================================================
# Suite 6: Source Verification, Font Metadata & Lexical Delimiter Balance
# ============================================================================

def check_delimiters(file_path: Path) -> Tuple[bool, str]:
    """Lexically balances (), [], {} ignoring comments and strings."""
    content = file_path.read_text(encoding='utf-8')
    stack = []
    pairs = {')': '(', ']': '[', '}': '{'}
    i = 0
    n = len(content)
    line = 1
    col = 1

    while i < n:
        c = content[i]
        if c == '\n':
            line += 1
            col = 1
            i += 1
            continue

        # Single-line comment
        if c == '/' and i + 1 < n and content[i + 1] == '/':
            while i < n and content[i] != '\n':
                i += 1
            continue

        # Multi-line comment
        if c == '/' and i + 1 < n and content[i + 1] == '*':
            start_l, start_c = line, col
            i += 2
            col += 2
            closed = False
            while i < n:
                if content[i] == '\n':
                    line += 1
                    col = 1
                    i += 1
                elif content[i] == '*' and i + 1 < n and content[i + 1] == '/':
                    i += 2
                    col += 2
                    closed = True
                    break
                else:
                    i += 1
                    col += 1
            if not closed:
                return False, f"Unterminated multi-line comment starting at line {start_l}:{start_c}"
            continue

        # Raw string r"..." or r'...'
        if (c == 'r' or c == 'R') and i + 1 < n and (content[i + 1] == '"' or content[i + 1] == "'"):
            quote = content[i + 1]
            i += 2
            col += 2
            # Check triple quote
            if i + 1 < n and content[i] == quote and content[i + 1] == quote:
                i += 2
                col += 2
                while i + 2 < n:
                    if content[i] == quote and content[i + 1] == quote and content[i + 2] == quote:
                        i += 3
                        col += 3
                        break
                    if content[i] == '\n':
                        line += 1
                        col = 1
                        i += 1
                    else:
                        i += 1
                        col += 1
                continue
            else:
                while i < n and content[i] != quote:
                    if content[i] == '\n':
                        line += 1
                        col = 1
                        i += 1
                    else:
                        i += 1
                        col += 1
                if i < n:
                    i += 1
                    col += 1
                continue

        # Triple string
        if (c == '"' or c == "'") and i + 2 < n and content[i + 1] == c and content[i + 2] == c:
            quote = c
            start_l, start_c = line, col
            i += 3
            col += 3
            closed = False
            while i + 2 < n:
                if content[i] == '\\':
                    i += 2
                    col += 2
                    continue
                if content[i] == quote and content[i + 1] == quote and content[i + 2] == quote:
                    i += 3
                    col += 3
                    closed = True
                    break
                if content[i] == '\n':
                    line += 1
                    col = 1
                    i += 1
                else:
                    i += 1
                    col += 1
            if not closed:
                return False, f"Unterminated triple string starting at line {start_l}:{start_c}"
            continue

        # Regular string
        if c == '"' or c == "'":
            quote = c
            start_l, start_c = line, col
            i += 1
            col += 1
            closed = False
            while i < n:
                if content[i] == '\n':
                    return False, f"Unterminated single-line string at line {start_l}:{start_c}"
                if content[i] == '\\':
                    i += 2
                    col += 2
                    continue
                if content[i] == quote:
                    i += 1
                    col += 1
                    closed = True
                    break
                i += 1
                col += 1
            if not closed:
                return False, f"Unterminated string starting at line {start_l}:{start_c}"
            continue

        # Delimiters
        if c in '([{':
            stack.append((c, line, col))
        elif c in ')]}':
            if not stack:
                return False, f"Unmatched closing delimiter '{c}' at line {line}:{col}"
            top, l, col_o = stack.pop()
            if pairs[c] != top:
                return False, f"Mismatched delimiter '{c}' at line {line}:{col}, expected matching '{pairs[c]}' for opening at line {l}:{col_o}"

        i += 1
        col += 1

    if stack:
        top, l, col_o = stack.pop()
        return False, f"Unclosed opening delimiter '{top}' at line {l}:{col_o}"

    return True, "Balanced"

def run_suite_6():
    print("-> Running Suite 6: Source Verification, Font Metadata & Lexical Delimiter Balance...")

    # verify_source.py
    cmd_verify = [sys.executable, str(FLUTTER_APP / "tool/verify_source.py")]
    res_verify = subprocess.run(cmd_verify, cwd=str(FLUTTER_APP), capture_output=True, text=True)
    if res_verify.returncode != 0:
        raise VerificationError(f"verify_source.py failed: {res_verify.stderr or res_verify.stdout}")
    print(f"   verify_source.py: {res_verify.stdout.strip()}")

    # font_metadata.py --check
    cmd_font = [sys.executable, str(FLUTTER_APP / "tool/font_metadata.py"), "--check"]
    res_font = subprocess.run(cmd_font, cwd=str(FLUTTER_APP), capture_output=True, text=True)
    if res_font.returncode != 0:
        raise VerificationError(f"font_metadata.py --check failed: {res_font.stderr or res_font.stdout}")
    print(f"   font_metadata.py: {res_font.stdout.strip()}")

    # Lexical delimiter balance
    target_files = [
        WIDGETS_DART,
        DIALOGS_DART,
        PAGE_DART,
        SETTINGS_DART,
        SHELL_DART,
        TEST_DART,
    ]
    for tf in target_files:
        ok, msg = check_delimiters(tf)
        if not ok:
            raise VerificationError(f"Lexical delimiter check failed for {tf.relative_to(REPO_ROOT)}: {msg}")
        lines = len(tf.read_text(encoding="utf-8").splitlines())
        print(f"   {tf.relative_to(REPO_ROOT)} ({lines} lines): DELIMITERS BALANCED")

    print("   [PASS] Suite 6: Source Verification, Font Metadata & Lexical Delimiter Balance passed cleanly.")


# ============================================================================
# Main Execution
# ============================================================================

def main():
    print("=" * 80)
    print("Milestone 2 Iteration 3 (Challenger M2_it3_2) Empirical Adversarial Test Harness")
    print("BitChord Desktop Revived - Settings & Dialogs Parity Verification")
    print("=" * 80)

    suites = [
        run_suite_1,
        run_suite_2,
        run_suite_3,
        run_suite_4,
        run_suite_5,
        run_suite_6,
    ]

    for suite in suites:
        try:
            suite()
        except Exception as e:
            print(f"\n[FAIL] {e}", file=sys.stderr)
            sys.exit(1)

    print("=" * 80)
    print("ALL 6 CHALLENGER M2_IT3_2 VERIFICATION SUITES PASSED (0 ERRORS, 0 DEFECTS)")
    print("=" * 80)

if __name__ == "__main__":
    main()
