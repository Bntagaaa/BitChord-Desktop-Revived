"""Port DesktopFontMetadata.kt's CFF classification fix, without touching glyphs.

The original bundled Bold font declares 600. Like the original desktop's
in-memory correction, generate a separate 700-classified copy for Skia. The
upstream font remains unchanged and all SFNT checksums remain valid.
"""
from pathlib import Path
import struct


def checksum(data):
    padded = data + bytes((-len(data)) % 4)
    return sum(struct.unpack(f">{len(padded) // 4}I", padded)) & 0xFFFFFFFF


def font_bytes_with_weight(data, weight):
    if not 1 <= weight <= 1000:
        raise ValueError("Font weight must be 1..1000")
    if len(data) < 12 or data[:4] != b"OTTO":
        raise ValueError("Expected an OpenType CFF font")
    count = struct.unpack_from(">H", data, 4)[0]
    if count > (len(data) - 12) // 16:
        raise ValueError("Invalid font table count")
    tables = {}
    for index in range(count):
        entry = 12 + index * 16
        tag, _, offset, length = struct.unpack_from(">4sIII", data, entry)
        if offset > len(data) or length > len(data) - offset:
            raise ValueError("Invalid font table range")
        tables[tag] = (entry, offset, length)
    os2 = tables.get(b"OS/2")
    if os2 is None or os2[2] < 6:
        raise ValueError("Missing OS/2 font table")
    if struct.unpack_from(">H", data, os2[1] + 4)[0] == weight:
        return data
    if b"DSIG" in tables:
        raise ValueError("Cannot reclassify a signed font")
    head = tables.get(b"head")
    if head is None or head[2] < 12:
        raise ValueError("Missing head font table")
    adjusted = bytearray(data)
    struct.pack_into(">H", adjusted, os2[1] + 4, weight)
    struct.pack_into(">I", adjusted, os2[0] + 4,
                     checksum(adjusted[os2[1]:os2[1] + os2[2]]))
    struct.pack_into(">I", adjusted, head[1] + 8, 0)
    struct.pack_into(">I", adjusted, head[0] + 4,
                     checksum(adjusted[head[1]:head[1] + head[2]]))
    struct.pack_into(">I", adjusted, head[1] + 8,
                     (0xB1B0AFBA - checksum(adjusted)) & 0xFFFFFFFF)
    return bytes(adjusted)


def verify(original, corrected):
    """Check the output, every table checksum, and preservation of all outlines/metrics."""
    expected = font_bytes_with_weight(original, 700)
    if corrected != expected or checksum(corrected) != 0xB1B0AFBA:
        raise ValueError("Derived font differs from the original desktop correction")
    count = struct.unpack_from(">H", original, 4)[0]
    for index in range(count):
        tag, expected_sum, offset, length = struct.unpack_from(">4sIII", corrected, 12 + index * 16)
        table = bytearray(corrected[offset:offset + length])
        if tag == b"head":
            table[8:12] = bytes(4)
        if checksum(table) != expected_sum:
            raise ValueError(f"Invalid {tag!r} table checksum")
        if tag not in (b"OS/2", b"head") and corrected[offset:offset + length] != original[offset:offset + length]:
            raise ValueError(f"Font outlines/metrics changed: {tag!r}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    fonts = Path(__file__).resolve().parents[1] / "assets/fonts"
    source = (fonts / "sf_pro_display_bold.otf").read_bytes()
    target = fonts / "sf_pro_display_bold_corrected.otf"
    if not args.check:
        target.write_bytes(font_bytes_with_weight(source, 700))
    verify(source, target.read_bytes())
    print("Verified Bold classification 700, unchanged glyph/metric tables and valid font checksums.")
