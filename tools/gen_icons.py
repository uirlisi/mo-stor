"""Generates Mo Stór's record icons and launcher-shortcut icons as Android resources, from the paths
approved in the icon review (2026-11-12, draft 2). The drawables in app/src/main/res are its output:
change the paths here, then run from the repository root:

    python -I tools/gen_icons.py app/src/main/res
"""
import sys
from pathlib import Path

def rect(x, y, w, h, r):
    r = min(r, w / 2, h / 2)
    return (f"M{x + r:g},{y:g}h{w - 2 * r:g}a{r:g},{r:g} 0,0 1,{r:g},{r:g}v{h - 2 * r:g}"
            f"a{r:g},{r:g} 0,0 1,{-r:g},{r:g}h{-(w - 2 * r):g}a{r:g},{r:g} 0,0 1,{-r:g},{-r:g}"
            f"v{-(h - 2 * r):g}a{r:g},{r:g} 0,0 1,{r:g},{-r:g}z")

DROP = "M12,2.6c-0.4,0.5 -6.8,8 -6.8,12.6a6.8,6.8 0,0 0,13.6 0C18.8,10.6 12.4,3.1 12,2.6z"
POOP = " ".join([rect(3, 16.2, 18, 5.3, 2.65), rect(5.5, 10.6, 13, 4.8, 2.4), rect(8, 5.6, 8, 4.2, 2.1),
                 "M10.2,5.9c0,-1.9 1.5,-3.4 4,-3.9 -0.6,1 -0.5,2.2 0.2,3.1 0.2,0.3 0.3,0.5 0.3,0.8z"])
BODY_R = ("M4.5,2.5H7.9C8.5,5.7 10.3,7.7 13.5,9.5C16.9,11.4 19.9,12.9 20.1,15.6H21C21.6,15.6 22,16.1 22,16.6"
          "C22,17.1 21.6,17.6 21,17.6H20.1C19.7,20.1 17.4,21.5 14.5,21.5H4.5Z")
BODY_L = ("M19.5,2.5H16.1C15.5,5.7 13.7,7.7 10.5,9.5C7.1,11.4 4.1,12.9 3.9,15.6H3C2.4,15.6 2,16.1 2,16.6"
          "C2,17.1 2.4,17.6 3,17.6H3.9C4.3,20.1 6.6,21.5 9.5,21.5H19.5Z")
LETTER_R = ("M8,12.6h2.9c1.5,0 2.5,0.9 2.5,2.2 0,1 -0.6,1.7 -1.5,2l1.8,2.6h-2.2l-1.6,-2.4v2.4H8z"
            "M9.9,14.1h1c0.5,0 0.8,0.3 0.8,0.8s-0.3,0.8 -0.8,0.8h-1z")
LETTER_L = "M11.6,12.6h1.9v5h2.7v1.8h-4.6z"

# Each icon: a list of elements. ("fill", d, evenodd) | ("stroke", d, width)
# | ("group", (tx, ty, s) or (tx, ty, s, degrees), [elements]); a rotation turns about the center (12, 12).
ICONS = {
    "ic_feed": [("fill", BODY_R, False)],
    "ic_feed_left": [("fill", BODY_L + LETTER_L, True)],
    "ic_feed_right": [("fill", BODY_R + LETTER_R, True)],
    "ic_bottle": [
        ("fill", "M10.4,5.2C10.4,3.3 11,2 12,2s1.6,1.3 1.6,3.2z", False),
        ("fill", rect(8.2, 5.7, 7.6, 2.3, 0.8), False),
        ("fill", rect(7, 8.7, 10, 13.3, 2.6) + "M9,12.1h3v1.2H9zM9,15.1h3v1.2H9zM9,18.1h3v1.2H9z", True),
    ],
    # Phase 3b: a capsule, left half solid and right half outlined, turned -45° (was a dropper).
    "ic_supplement": [("group", (0, 0, 1, -45), [
        ("fill", "M12,8H7a4,4 0 0 0 0,8h5z", False),
        ("stroke", "M12,8.9h5a3.1,3.1 0 0 1 0,6.2h-5z", 1.8),
    ])],
    "ic_sleep": [
        ("fill", "M20.4,14.8A8.6,8.6 0,1 1,9.2 3.6a7,7 0,0 0,11.2 11.2z", False),
        ("fill", "M17.6,2.8l0.7,1.8 1.8,0.7 -1.8,0.7 -0.7,1.8 -0.7,-1.8 -1.8,-0.7 1.8,-0.7z", False),
    ],
    "ic_awake": [
        ("fill", "M12,7.7a4.3,4.3 0,1 0,0 8.6a4.3,4.3 0,1 0,0 -8.6z", False),
        ("stroke", "M12,2v2.4M12,19.6V22M2,12h2.4M19.6,12H22M4.9,4.9l1.7,1.7M17.4,17.4l1.7,1.7M4.9,19.1l1.7,-1.7M17.4,6.6l1.7,-1.7", 2),
    ],
    "ic_diaper": [("fill", "M3,5.4h18v3.2c0,6.2 -4,11 -9,11s-9,-4.8 -9,-11zM3,8.3v1.2h18V8.3z", True)],
    "ic_wet": [("fill", DROP, False)],
    "ic_poop": [("fill", POOP, False)],
    "ic_mixed": [("group", (0.4, 0.2, 0.6), [("fill", DROP, False)]), ("group", (6.6, 6.6, 0.72), [("fill", POOP, False)])],
    # The two halves of the mixed icon, so charts can color them as wet and poop.
    "ic_mixed_part_wet": [("group", (0.4, 0.2, 0.6), [("fill", DROP, False)])],
    "ic_mixed_part_poop": [("group", (6.6, 6.6, 0.72), [("fill", POOP, False)])],
    "ic_dry": [
        ("stroke", "M3.5,5.5h17v3c0,5.9 -3.8,10.6 -8.5,10.6S3.5,14.4 3.5,8.5z", 1.9),
        ("stroke", "M3.5,9h17", 1.6),
    ],
    "ic_pump": [
        ("fill", "M2.4,4.3 L9.4,8.7v3.6l-7,4.4c-0.6,0.4 -1.4,0 -1.4,-0.7V5c0,-0.7 0.8,-1.1 1.4,-0.7z", False),
        ("fill", rect(9.2, 7.6, 6, 5.8, 1.2), False),
        ("fill", "M15,8.1l5.7,-3.5 0.9,1.5 -5.7,3.5z", False),
        ("fill", "M11.2,13.3h2.4v1.4h-2.4z", False),
        ("fill", rect(9.6, 14.5, 5.6, 7.5, 1.5), False),
    ],
    "ic_note": [
        ("fill", "M6,2.5h8.3v5.6h5.2V21a0.5,0.5 0,0 1,-0.5 0.5H6a0.5,0.5 0,0 1,-0.5 -0.5V3a0.5,0.5 0,0 1,0.5 -0.5z"
                 "M8.4,11.2h7.2v1.4H8.4zM8.4,14.6h7.2V16H8.4zM8.4,18h4.6v1.4H8.4z", True),
        ("fill", "M15.3,2.9l4,4h-4z", False),
    ],
}

DISC = "M12,2a10,10 0 1,0 0,20a10,10 0 1,0 0,-20z"
# Phase 3b: Meds marks and the running-timer controls (docs/PHASE3B_HANDOFF.md, "Icons").
ICONS.update({
    "ic_given": [("fill", DISC + "M7.79,10.91L10.3,13.42L16.21,7.51L18.19,9.49L10.3,17.38L5.81,12.89z", True)],
    "ic_due": [("fill", DISC + "M12,4.6a7.4,7.4 0 1,1 0,14.8a7.4,7.4 0 1,1 0,-14.8z", True)],
    "ic_overdue": [("fill", DISC + "M10.7,6.4h2.6v7.6h-2.6z"
                    "M12,15.5a1.55,1.55 0 1,0 0,3.1a1.55,1.55 0 1,0 0,-3.1z", True)],
    # A skipped dose (DECISIONS 2026-11-13): a disc with an x cut out, drawn as a plus turned 45°.
    "ic_skipped": [("group", (0, 0, 1, 45), [
        ("fill", DISC + "M10.7,7.4h2.6v3.3h3.3v2.6h-3.3v3.3h-2.6v-3.3H7.4v-2.6h3.3z", True),
    ])],
    "ic_pause": [("fill", rect(6, 4.5, 4.4, 15, 1.4) + rect(13.6, 4.5, 4.4, 15, 1.4), False)],
    "ic_resume": [("fill", "M8,5.2v13.6a1.2,1.2 0 0 0 1.85,1l10.4,-6.8a1.2,1.2 0 0 0 0,-2"
                   "l-10.4,-6.8a1.2,1.2 0 0 0 -1.85,1z", False)],
    "ic_stop": [("fill", rect(5, 5, 14, 14, 2.5), False)],
    "ic_switch_side": [("stroke", "M4,8h14M14.5,4.5L18,8l-3.5,3.5M20,16H6M9.5,12.5L6,16l3.5,3.5", 2.2)],
})

def element(e, color, indent):
    pad = " " * indent
    kind = e[0]
    if kind == "fill":
        _, d, evenodd = e
        rule = f'\n{pad}    android:fillType="evenOdd"' if evenodd else ""
        return f'{pad}<path\n{pad}    android:fillColor="{color}"{rule}\n{pad}    android:pathData="{d}" />'
    if kind == "stroke":
        _, d, w = e
        return (f'{pad}<path\n{pad}    android:strokeColor="{color}"\n{pad}    android:strokeWidth="{w}"\n'
                f'{pad}    android:strokeLineCap="round"\n{pad}    android:strokeLineJoin="round"\n{pad}    android:pathData="{d}" />')
    _, transform, inner = e
    tx, ty, s = transform[:3]
    body = "\n".join(element(x, color, indent + 4) for x in inner)
    turn = ""
    if len(transform) > 3:
        turn = f'\n{pad}    android:rotation="{transform[3]}"\n{pad}    android:pivotX="12"\n{pad}    android:pivotY="12"'
    return (f'{pad}<group\n{pad}    android:translateX="{tx}"\n{pad}    android:translateY="{ty}"\n'
            f'{pad}    android:scaleX="{s}"\n{pad}    android:scaleY="{s}"{turn}>\n{body}\n{pad}</group>')

HEADER = "<!-- Mo Stór record icon, drawn for the app (icon review, 2026-11-12). Generated from one set of paths; tinted where used. -->"

def vector(name, elements):
    body = "\n".join(element(e, "@android:color/white", 4) for e in elements)
    return (f'<?xml version="1.0" encoding="utf-8"?>\n{HEADER}\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp"\n    android:height="24dp"\n'
            '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
            f"{body}\n</vector>\n")

# Launcher shortcuts: the glyph in its color, centered in the adaptive icon's 108 dp canvas, on a pale disc.
SHORTCUTS = {
    "wet": ("ic_wet", "#FF1E88E5", "#FFDBECFB"),
    "poop": ("ic_poop", "#FF795548", "#FFEAE4E2"),
    "left": ("ic_feed_left", "#FF00796B", "#FFD6EAE7"),
    "right": ("ic_feed_right", "#FF00796B", "#FFD6EAE7"),
}

def shortcut_foreground(icon, color):
    s = 2.3
    t = (108 - 24 * s) / 2
    inner = "\n".join(element(e, color, 8) for e in ICONS[icon])
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Launcher shortcut glyph ({icon}), in the 66 dp safe zone. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            f'    <group\n        android:translateX="{t:g}"\n        android:translateY="{t:g}"\n'
            f'        android:scaleX="{s}"\n        android:scaleY="{s}">\n{inner}\n    </group>\n</vector>\n')

def shortcut_icon(key, bg):
    return ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Launcher shortcut icon: the glyph on a pale disc (the launcher masks it round). -->\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <background android:drawable="@color/shortcut_{key}_bg" />\n'
            f'    <foreground android:drawable="@drawable/shortcut_fg_{key}" />\n</adaptive-icon>\n')

def main(res):
    d = Path(res) / "drawable"
    for name, elements in ICONS.items():
        (d / f"{name}.xml").write_text(vector(name, elements), encoding="utf-8", newline="\n")
    colors = []
    for key, (icon, color, bg) in SHORTCUTS.items():
        (d / f"shortcut_fg_{key}.xml").write_text(shortcut_foreground(icon, color), encoding="utf-8", newline="\n")
        (d / f"shortcut_{key}.xml").write_text(shortcut_icon(key, bg), encoding="utf-8", newline="\n")
        colors.append(f'    <color name="shortcut_{key}_bg">{bg}</color>')
    (Path(res) / "values" / "shortcut_colors.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<!-- Pale discs behind the launcher shortcut glyphs. -->\n<resources>\n'
        + "\n".join(colors) + "\n</resources>\n", encoding="utf-8", newline="\n")
    print(len(ICONS), "icons,", len(SHORTCUTS), "shortcuts")

main(sys.argv[1])
