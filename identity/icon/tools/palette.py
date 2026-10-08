#!/usr/bin/env python3
"""Recolour the traced globe icon, place it on the tile and emit the drop.

The trace (`theirs.svg`) is 94 flat paths: the tile, and 93 facets that fall into
four families -- left/right half x land/ocean -- found by clustering hue,
saturation and lightness within each half.  A palette is then a hue and a
saturation per family, plus the background; the trace itself supplies all the
facet-to-facet variation, so the low-poly shading survives the recolour:

    out_hue   = family hue + HUE_SPREAD * (src hue - family mean hue)
    out_sat   = family sat + SAT_SPREAD * GAIN_sat * (src sat - family mean sat)
    out_light = BAND_lo + (BAND_hi - BAND_lo) * spread(src light within its half)

THE LEFT HALF IS ALWAYS THE DARKER, FLATTER ONE.  BAND puts every left facet
below every right one, and GAIN compresses the left's facets towards the middle
of their band (< 1) while pushing the right's apart (> 1): the left reads as one
dark mass, the right as many separate facets.  `check()` proves the darkness rule
for every palette before anything is written.

Placement: the trace's own canvas (234x239) is *not* centred on its ink -- the
artwork sits 4.1 units left of it -- so the drop is centred on the ink instead,
and on the ink's optical centre rather than its bounding-box centre.  See PLACE.

  python3 identity/icon/tools/palette.py          # rewrite palettes/ and the three icons
  python3 identity/icon/tools/palette.py --debug  # land/ocean masks, to check the clustering

Paths are written to this drop, not to the cwd: `palettes/*.svg`, plus
`tulkki-icon.svg`, `tulkki-icon-art.svg` and `tulkki-icon-mono.svg`.
"""
import colorsys
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DROP = HERE.parent
TRACE = HERE / "theirs.svg"

CANVAS = 240.0        # every SVG shares this canvas
SCALE = 0.96          # the artwork's scale on it
BG = "#08111B"        # the trace's own tile fill; not a palette colour
HUE_W = 6.0           # hue weight in the clustering
HUE_SPREAD = 0.60     # how much of the trace's own hue variation survives
SAT_SPREAD = 0.60     # ... and of its saturation

# Per-side (lightness, saturation, hue) gain on the facet-to-facet variation:
# < 1 pulls a half's facets together, > 1 pushes them apart.  The asymmetry
# between these two triples is the design -- do not level it up.
GAIN = {"left": (0.85, 0.55, 0.35), "right": (1.15, 0.95, 0.75)}

# Where each half's facets may land, on a 0..1 lightness scale.  The gap between
# the two bands is the invariant: the brightest left facet is still darker than
# the darkest right one.
BAND = {"left": (0.13, 0.39), "right": (0.45, 0.81)}

# ---- placement ------------------------------------------------------------
# Optical centre.  The tail hangs below the globe, so the bounding box is not
# what the eye centres on: GLOBE_BOTTOM is where the outline stops being the
# globe and starts being the pointer (measured on the trace as the last row that
# is still at least 40% of the artwork's width), and TAIL_WEIGHT is how much of
# the pointer's overhang counts as mass.  0 = centre the bounding box (the globe
# then sits high), 1 = centre the globe (the tail then crowds the tile edge).
GLOBE_BOTTOM = 164.4
TAIL_WEIGHT = 0.0
NUDGE = (0.0, 0.0)    # extra hand nudge in canvas units, +x right, +y down


def hls(hexv):
    """Hue, saturation, lightness -- NOT rgb_to_hls' own (h, l, s) order."""
    r, g, b = (int(hexv[i:i + 2], 16) / 255 for i in (1, 3, 5))
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    return h, s, l


def hexf(h, s, l):
    h, s, l = h % 1.0, clamp(s), clamp(l)
    r, g, b = colorsys.hls_to_rgb(h, l, s)
    return "#%02X%02X%02X" % tuple(round(c * 255) for c in (r, g, b))


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


# ---- exact geometry -------------------------------------------------------
# The trace uses only M, relative c and Z, so a path's bounding box is exact
# without rasterising anything: cubic endpoints, plus the stationary points of
# each segment.
def _cubic_roots(p0, p1, p2, p3):
    a = -p0 + 3 * p1 - 3 * p2 + p3
    b = 2 * (p0 - 2 * p1 + p2)
    c = p1 - p0
    if abs(a) < 1e-12:
        return [-c / b] if abs(b) > 1e-12 else []
    disc = b * b - 4 * a * c
    if disc < 0:
        return []
    root = disc ** 0.5
    return [(-b + root) / (2 * a), (-b - root) / (2 * a)]


def _cubic_at(p0, p1, p2, p3, t):
    u = 1 - t
    return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3


def path_bbox(d):
    toks = re.findall(r"[MmCcZz]|-?\d*\.?\d+", d)
    xs, ys = [], []
    x = y = 0.0
    i = 0

    def add(px, py):
        xs.append(px)
        ys.append(py)

    while i < len(toks):
        cmd = toks[i]
        if cmd in "Mm":
            x, y = float(toks[i + 1]), float(toks[i + 2])
            add(x, y)
            i += 3
        elif cmd in "Cc":
            dx1, dy1, dx2, dy2, dx3, dy3 = (float(v) for v in toks[i + 1:i + 7])
            p = (x, y, x + dx1, y + dy1, x + dx2, y + dy2, x + dx3, y + dy3)
            for t in set(_cubic_roots(p[0], p[2], p[4], p[6])
                         + _cubic_roots(p[1], p[3], p[5], p[7])):
                if 0.0 < t < 1.0:
                    add(_cubic_at(p[0], p[2], p[4], p[6], t),
                        _cubic_at(p[1], p[3], p[5], p[7], t))
            x, y = p[6], p[7]
            add(x, y)
            i += 7
        elif cmd in "Zz":
            i += 1
        else:
            sys.exit("path_bbox: unexpected command %r in %s..." % (cmd, d[:40]))
    return min(xs), min(ys), max(xs), max(ys)


def union_bbox(paths):
    boxes = [path_bbox(d) for _fill, d in paths]
    return (min(b[0] for b in boxes), min(b[1] for b in boxes),
            max(b[2] for b in boxes), max(b[3] for b in boxes))


# ---- the trace ------------------------------------------------------------
def read_trace(path):
    text = Path(path).read_text()
    paths = re.findall(r'<path fill="(#[0-9a-fA-F]{6})"[^>]*?d="([^"]+)"', text)
    if not paths:
        sys.exit("palette.py: no <path fill=... d=...> in %s" % path)
    art = [p for p in paths if p[0].upper() != BG]
    return paths, art


PATHS, ART = read_trace(TRACE)
INK = union_bbox(ART)


def placement():
    """(tx, ty) of the artwork group on the canvas, from the rules above."""
    ink_cx = (INK[0] + INK[2]) / 2.0
    visual_bottom = INK[3] - TAIL_WEIGHT * (INK[3] - GLOBE_BOTTOM)
    ink_cy = (INK[1] + visual_bottom) / 2.0
    return (CANVAS / 2.0 + NUDGE[0] - SCALE * ink_cx,
            CANVAS / 2.0 + NUDGE[1] - SCALE * ink_cy)


TX, TY = placement()

# ---- clustering -----------------------------------------------------------
cols = {}
for fill, _d in PATHS:
    if fill.upper() == BG:
        continue
    h, s, l = hls(fill)
    cols[fill.upper()] = (h, s, l, "left" if h < 0.575 else "right")


def kmeans2(points, iters=60):
    pts = sorted(points, key=lambda p: p["h"])
    cs = [dict(pts[0]), dict(pts[-1])]
    for _ in range(iters):
        groups = [[], []]
        for p in pts:
            d = [HUE_W * (p["h"] - c["h"]) ** 2 + (p["s"] - c["s"]) ** 2 + (p["l"] - c["l"]) ** 2
                 for c in cs]
            groups[0 if d[0] <= d[1] else 1].append(p)
        for i, g in enumerate(groups):
            if g:
                cs[i] = {k: sum(p[k] for p in g) / len(g) for k in ("h", "s", "l")}
    return groups


family = {}
for side in ("left", "right"):
    pts = [dict(h=h, s=s, l=l, key=k) for k, (h, s, l, sd) in cols.items() if sd == side]
    groups = kmeans2(pts)
    # the ocean covers more of the sphere than the land, so it owns more colours
    groups.sort(key=len, reverse=True)
    for name, g in zip(("ocean", "land"), groups):
        for p in g:
            family[p["key"]] = (side, name)

MEAN = {}
for key, (side, name) in family.items():
    h, s, l = cols[key][:3]
    st = MEAN.setdefault((side, name), {"h": [], "s": [], "l": []})
    st["h"].append(h)
    st["s"].append(s)
    st["l"].append(l)
MEAN = {k: {f: sum(v[f]) / len(v[f]) for f in ("h", "s", "l")} for k, v in MEAN.items()}
SIDE_RANGE = {}
for key, (side, _name) in family.items():
    lo, hi = SIDE_RANGE.get(side, (1.0, 0.0))
    l = cols[key][2]
    SIDE_RANGE[side] = (min(lo, l), max(hi, l))


def P(bg, l_ocean, l_land, r_ocean, r_land):
    """A palette: background plus (hue, saturation) per family.

    Hue and saturation are both 0..1 (`hue_flip` adds 0.5 to a hue).  Lightness
    is not a per-palette choice -- BAND and GAIN above own it, which is what
    keeps the left half the dark one in every palette.
    """
    spec = {"bg": bg, "left/ocean": l_ocean, "left/land": l_land,
            "right/ocean": r_ocean, "right/land": r_land}
    for name, value in spec.items():
        if name != "bg" and not (0.0 <= value[0] <= 1.0 and 0.0 <= value[1] <= 1.0):
            sys.exit("palette.py: %s out of range in %s" % (name, spec))
    return spec


# The palette wired into the icon: tools/icon.py, preview.py and sheet.py all
# read this, so there is exactly one place to change the shipped colourway.
CHOSEN = "maailma"

#           bg          left ocean      left land       right ocean     right land
PALETTES = {
    # The earth one, on a dark tile: a legible globe on the right -- green
    # continents on an azure sea, lit -- and the same world on the left, in
    # shadow: desaturated slate water under dark umber land.  Dark tile rather
    # than pale so the seam between the halves reads as the gap it is, and so the
    # lit half carries the icon.
    "maailma": P("#0A121E", (0.589, 0.34), (0.083, 0.32), (0.589, 0.62), (0.298, 0.45)),
    # The same ink on the pale tile, if the dark one is ever not wanted.
    "päivä":   P("#E5EEF7", (0.589, 0.34), (0.083, 0.32), (0.589, 0.62), (0.298, 0.45)),
    "hero":    P("#08111B", (0.47, 0.62), (0.24, 0.52), (0.63, 0.62), (0.70, 0.60)),
    "aurora":  P("#071019", (0.45, 0.62), (0.32, 0.55), (0.70, 0.60), (0.80, 0.58)),
    "ember":   P("#1B0F0C", (0.05, 0.62), (0.09, 0.60), (0.99, 0.62), (0.05, 0.65)),
    "gold":    P("#0E1626", (0.60, 0.55), (0.11, 0.60), (0.58, 0.45), (0.11, 0.55)),
    "ice":     P("#0B1524", (0.58, 0.50), (0.56, 0.28), (0.57, 0.38), (0.55, 0.16)),
    "noir":    P("#0A0A0A", (0.60, 0.03), (0.60, 0.03), (0.60, 0.03), (0.60, 0.03)),
    "forest":  P("#0C1A12", (0.42, 0.50), (0.30, 0.45), (0.45, 0.45), (0.28, 0.50)),
    "rose":    P("#1A0F16", (0.93, 0.50), (0.97, 0.40), (0.87, 0.55), (0.90, 0.45)),
    "tuli":    P("#0B1524", (0.06, 0.62), (0.11, 0.68), (0.58, 0.60), (0.53, 0.45)),
    "meri":    P("#050F1C", (0.62, 0.62), (0.55, 0.55), (0.53, 0.60), (0.50, 0.45)),
    "laku":    P("#111111", (0.62, 0.40), (0.09, 0.40), (0.60, 0.80), (0.58, 0.65)),
    "sammal":  P("#101A14", (0.35, 0.52), (0.20, 0.50), (0.45, 0.40), (0.15, 0.55)),
    "kanerva": P("#170F1D", (0.70, 0.55), (0.76, 0.45), (0.94, 0.40), (0.10, 0.55)),
    "savi":    P("#201512", (0.03, 0.45), (0.07, 0.55), (0.05, 0.50), (0.09, 0.45)),
    "sininen": P("#06101E", (0.60, 0.60), (0.55, 0.45), (0.58, 0.55), (0.52, 0.40)),
    "violetti": P("#120A1E", (0.75, 0.55), (0.80, 0.50), (0.72, 0.60), (0.85, 0.50)),
    "sitruuna": P("#14180E", (0.20, 0.55), (0.15, 0.68), (0.18, 0.60), (0.12, 0.65)),
    "veri":    P("#100506", (0.99, 0.65), (0.02, 0.60), (0.97, 0.68), (0.01, 0.55)),
    "minttu":  P("#06130F", (0.45, 0.55), (0.38, 0.55), (0.48, 0.50), (0.42, 0.45)),
    "teräs":   P("#10151A", (0.58, 0.16), (0.58, 0.11), (0.58, 0.14), (0.58, 0.09)),
    "paper":   P("#F2EFE7", (0.58, 0.50), (0.09, 0.55), (0.03, 0.60), (0.05, 0.55)),
    "sumu":    P("#E6E2DA", (0.58, 0.35), (0.13, 0.40), (0.78, 0.30), (0.95, 0.25)),
    "koralli": P("#F7EEE5", (0.467, 0.60), (0.542, 0.48), (0.983, 0.62), (0.061, 0.60)),
    "hiekka":  P("#F5EFE2", (0.09, 0.45), (0.11, 0.55), (0.08, 0.50), (0.10, 0.45)),
}


def hue_flip(spec, deg=180.0):
    """Rotate the hue of the whole icon -- background included."""
    shift = deg / 360.0
    out = dict(spec)
    hb, sb, lb = hls(spec["bg"])
    out["bg"] = hexf(hb + shift, sb, lb)
    for key in ("left/ocean", "left/land", "right/ocean", "right/land"):
        h, s = spec[key]
        out[key] = ((h + shift) % 1.0, s)
    return out


PALETTES["koralli-inv"] = hue_flip(PALETTES["koralli"])


def colour_of(spec, key):
    """The palette's colour for one of the trace's fills."""
    h, s, l, _sd = cols[key]
    side, fam = family[key]
    th, ts = spec["%s/%s" % (side, fam)]
    dev = MEAN[(side, fam)]
    gain_l, gain_s, gain_h = GAIN[side]
    lo, hi = BAND[side]
    rlo, rhi = SIDE_RANGE[side]
    t = clamp((l - rlo) / (rhi - rlo)) if rhi > rlo else 0.5
    t = clamp(0.5 + gain_l * (t - 0.5))
    return hexf(th + HUE_SPREAD * gain_h * (h - dev["h"]),
                ts + SAT_SPREAD * gain_s * (s - dev["s"]),
                lo + (hi - lo) * t)


def check(spec, name):
    """Prove the design's one hard rule for this palette: left darker than right."""
    left = [hls(colour_of(spec, k))[2] for k in cols if family[k][0] == "left"]
    right = [hls(colour_of(spec, k))[2] for k in cols if family[k][0] == "right"]
    assert max(left) < min(right), (
        "%s: left half must stay dark (left max %.3f, right min %.3f)"
        % (name, max(left), min(right)))
    return max(left), min(right)


def render(spec, debug=False, mode="icon"):
    """One SVG.  mode: icon (tile baked in), art (transparent), mono (white)."""
    lookup = {}
    for key in cols:
        if debug:
            lookup[key] = "#FF2D95" if family[key][1] == "land" else "#12E1E1"
        elif mode == "mono":
            lookup[key] = "#FFFFFF"
        else:
            lookup[key] = colour_of(spec, key)
    bg = (spec or {}).get("bg", "#101010")
    out = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %g %g" width="%g" height="%g">'
           % (CANVAS, CANVAS, CANVAS, CANVAS)]
    if mode == "icon":
        out.append('<rect width="%g" height="%g" fill="%s"/>' % (CANVAS, CANVAS, bg))
    out.append('<g transform="translate(%.4f,%.4f) scale(%.4f)">' % (TX, TY, SCALE))
    for fill, d in PATHS:
        if mode != "icon" and fill.upper() == BG:
            continue
        new = bg if fill.upper() == BG else lookup.get(fill.upper(), fill)
        out.append('<path fill="%s" stroke="%s" stroke-width="0.5" d="%s"/>' % (new, new, d))
    out.append("</g></svg>")
    return "\n".join(out) + "\n"


def main():
    if "--debug" in sys.argv:
        renders = DROP / "renders"
        renders.mkdir(exist_ok=True)
        svg = renders / "debug-families.svg"
        svg.write_text(render(None, debug=True))
        print("wrote %s (land = magenta, ocean = cyan)" % svg.relative_to(DROP))
        png = renders / "debug-families.png"
        try:    # rsvg-convert may not be installed; the SVG is the source of truth
            subprocess.run(["rsvg-convert", "-w", "400", "-h", "400", "-o", str(png), str(svg)],
                           check=True)
            print("wrote %s" % png.relative_to(DROP))
        except (OSError, subprocess.CalledProcessError):
            print("rsvg-convert missing: only the SVG was written")
        return

    named = sorted(PALETTES)
    fams = ("left/ocean", "left/land", "right/ocean", "right/land")
    pal = DROP / "palettes"
    pal.mkdir(exist_ok=True)
    for old in pal.glob("*.svg"):
        old.unlink()
    rows = []
    for name in named:
        spec = PALETTES[name]
        hi, lo = check(spec, name)
        (pal / ("%s.svg" % name)).write_text(render(spec))
        fam = {}
        for key in cols:
            side, f = family[key]
            h, s, l = hls(colour_of(spec, key))
            b = fam.setdefault("%s/%s" % (side, f), ([], [], []))
            b[0].append(h * 360)
            b[1].append(s * 100)
            b[2].append(l * 100)
        rows.append((name, spec["bg"], fam, hi, lo))
    print("%-13s %-8s %-24s %-24s" % ("palette", "bg", "left (hue/sat/light)",
                                      "right (hue/sat/light)"))
    for name, bg, fam, hi, lo in rows:
        def span(pair, k):
            v = fam[pair][k]
            return "%3.0f-%3.0f" % (min(v), max(v))
        left = "  ".join("%s %s/%s/%s" % (p.split("/")[1][:4], span(p, 0), span(p, 1), span(p, 2))
                         for p in ("left/ocean", "left/land"))
        right = "  ".join("%s %s/%s/%s" % (p.split("/")[1][:4], span(p, 0), span(p, 1), span(p, 2))
                          for p in ("right/ocean", "right/land"))
        print("%-13s %-8s %-24s %-24s  %s" % (name, bg, left, right,
                                              "left<right OK" if hi < lo else "BROKEN"))
    print("wrote %d palettes to palettes/" % len(named))

    chosen = PALETTES[CHOSEN]
    print("wired in: %s  (bg %s)" % (CHOSEN, chosen["bg"]))
    for mode, name in (("icon", "tulkki-icon.svg"),
                       ("art", "tulkki-icon-art.svg"),
                       ("mono", "tulkki-icon-mono.svg")):
        (DROP / name).write_text(render(chosen, mode=mode))
        print("wrote %s" % name)
    print("artwork ink: %.2f x %.2f at (%.2f, %.2f) source units -> "
          "canvas (%.2f, %.2f), translate(%.4f,%.4f) scale(%.2f)"
          % (INK[2] - INK[0], INK[3] - INK[1], INK[0], INK[1],
             TX + SCALE * INK[0], TY + SCALE * INK[1], TX, TY, SCALE))


if __name__ == "__main__":
    main()
