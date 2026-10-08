# Tulkki app icon

Source artwork for the launcher icon, and the one script that turns it into the
app's Android resources.

The artwork is a low-poly globe speech bubble, split by a seam into two
hemispheres in different palettes. The chosen palette is **maailma** — the earth
one: green continents on an azure sea on the right, the same world on the left in
shadow (desaturated slate water under dark umber land), on a dark `#0A121E` tile,
so the seam between the halves reads as the dark split it is. **The left half is
always the darker, flatter one**; that asymmetry is the design.

## Files

Paths are from the repo root; the first four are this directory.

| file | what it is |
|---|---|
| `identity/icon/tulkki-icon.svg` | **The icon.** 240×240, background baked in. Ship this. |
| `identity/icon/tulkki-icon-art.svg` | Same artwork, no background, transparent — for compositing, and the file the build measures its geometry from. |
| `identity/icon/tulkki-icon-mono.svg` | Single-colour silhouette (white, transparent) — monochrome / themed icons, notification glyphs. |
| `identity/icon/README.md` | This file. |
| `tools/icon.py` | **The generator.** Those three SVGs in, the app's Android resources out, plus `--preview` to look at the result. |
| `identity/icon/tools/palette.py` | The recolour model — how a palette is stamped onto the traced master. It needs that master beside it to run; see [Recolouring](#recolouring). |

Both scripts find the repo from their own location and write relative to it, so
**their paths are load-bearing**: `tools/icon.py` must stay in the repo's `tools/`
(it writes `ui/src/main/res/…` and `app/src/main/res/…`), and `palette.py` must
stay beside the artwork it regenerates (it writes the three SVGs one directory up,
into this one).

The artwork was traced from a reference sheet, and the pipeline that produced it
(the traced master, the `cut`/`trace` steps, and a 27-colourway exploration sheet
with its previews) has been deleted. **These three SVGs are the artwork now**:
they are the source of truth, and nothing here regenerates them from a raster.

## Geometry

Every SVG shares one coordinate system, so they can be swapped or overlaid directly:

- canvas `240 × 240`; the artwork sits at `translate(9.2996,6.4308) scale(0.96)`
- the artwork's paths bound **179.08 × 152.54 at (30.46, 43.73)** canvas units;
  with the 0.5 self-stroke each facet draws, the *visible* ink is
  **180.0 × 153.5 at (30.0, 43.3)** — margins 30.0 / 30.0 sideways and 43.3 / 43.5
  top and bottom, i.e. centred to within the quarter-unit a raster measurement can
  resolve. That second pair is the one the build's scale comes from.
- the seam between the hemispheres is a ~6.9-unit gap that shows the tile colour
  through it, exactly as in the reference sheet; it lands at x = 119.7, i.e. on
  the canvas centre
- 94 flat paths — the tile, then 93 facets — no gradients or clips; each path
  strokes itself in its own fill colour at `0.5`, which hides the hairline seams
  between adjacent facets
- only `M`, `c` and `Z` path commands are used, so the paths drop into any consumer
  (including Android `VectorDrawable` `pathData`) unmodified

**What "centred" means here.** The artwork is centred on its **ink bounding box**.
The trace's own 234×239 canvas was not centred on its ink — the artwork sat 4.1
units left of it — so an earlier version, which centred that canvas, put the whole
icon 4.1 units (1.7% of the tile) left of centre, and off-centre inside the
adaptive mask too, because the mask is centred on the layer. Centring the ink is
also what centres the seam, since the gap's own centre is within 0.2 units of the
outline's. It deliberately does *not* centre the globe alone: the tail hangs below
the globe, and centring the globe would push the tail to 28 units of the tile edge
and up against the mask. At 48 px the result reads as a disc with an even margin
all round, which is the point.

**Adaptive layer.** `tools/icon.py` measures the ink off `tulkki-icon-art.svg` and
scales it so that 180-unit width spans **`INK_FRACTION = 0.88` of the 66 dp safe
circle** — 58.1 dp, scale `0.3227`, translate `(15.28,15.28)` — with the offset
derived from the measured ink centre, so the ink sits on the middle of the 108 dp
layer whatever the fraction is. The circle is the zone that is *guaranteed* to
survive every launcher mask, not a target to fill: at `1.00` the globe ran
edge-to-edge under the round and squircle masks, and 0.88 is the value picked by
eye (0.92 still reads full, 0.80 reads as a ball in a tile). One constant; move it
and nothing else needs touching — `--preview` shows the result. A notification
glyph, authored in the same 108-unit viewport and unrelated to that constant,
wants `88/180 = 0.489` to fill a 24 dp slot.

## Generating the Android resources

```
python3 tools/icon.py                        # writes the ui/ and app/ res trees + the Play image
python3 tools/icon.py --preview              # a picture of the adaptive layer, in a temp dir
python3 tools/icon.py --preview icon.png     # ... or at a path you name
```

`--preview` is how you *look* at a change. The foreground is a VectorDrawable,
which is not a picture, so this is the only thing in the tree that shows the
artwork under the launcher's masks: the 108 dp layer at 4 px/dp with the 66 dp
safe circle and the 72 dp crop drawn on it, the same layer under the circle and
squircle masks, and the 48 dp result beside the legacy raster. It draws from the
same measured scale and offset the drawables are written with, writes nowhere in
the tree, and needs only what generation already needs — `rsvg-convert` and PIL.
Note `/tmp` is a fresh tmpfs in every DSH shell, so pass a path if the picture has
to outlive the shell it was made in.

A plain run prints the geometry it derived every time — the ink's size, the dp it
occupies of the safe circle, the scale, and where the ink centre lands — so a
regression in placement or size is visible without rendering anything.

## Recolouring

`identity/icon/tools/palette.py` holds the recolour model. It clusters the artwork's facets into
four families (left/right half × land/ocean), then applies a hue and a saturation
per family plus a background, with the trace itself supplying the facet-to-facet
variation:

```
out_hue   = family hue + 0.60 * (src hue - family mean hue)
out_sat   = family sat + 0.60 * GAIN_sat * (src sat - family mean sat)
out_light = BAND_lo + (BAND_hi - BAND_lo) * spread(src light within its own half)
```

`BAND` (left `0.13–0.39`, right `0.45–0.81`) and `GAIN` (left `0.85/0.55/0.35`,
right `1.15/0.95/0.75` — lightness/saturation/hue) are global, and they are what
keeps the left half the dark, flat one: the left's facets are pulled towards each
other while the right's are pushed apart, and no left facet can reach the right
half's band. `check()` proves that rule for every palette before anything is
written. `hue_flip(spec, deg)` rotates a whole palette, background included.

**Check its input before you run it.** It reads the traced master,
`tools/theirs.svg`; without that file beside it the script stops at import with a
`FileNotFoundError` (the trace is not in the tree as this drop stands). Put a
trace back and it rewrites the three SVGs plus `palettes/*.svg`. One of the three
surviving SVGs will *not* do as a substitute: in a rendered icon the tile path
no longer carries the trace's background fill, so it is counted as artwork, the
"ink" box becomes the whole canvas, and the placement recentres the canvas —
reintroducing the 4.1-unit bug described above. The model is written down here so that it can be rebuilt or
replaced deliberately rather than re-derived.

## Android resources

`tools/icon.py` reads exactly the three SVGs above and writes the app's Android
resources, all of them committed. The 3.8 module split gave each tree its own
module, so they no longer sit together:

- `ui/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml`
- `ui/src/main/res/drawable/ic_notification.xml` — the silhouette as a **24 dp
  notification glyph**, in the same 108-unit viewport at the `88/180` span above,
  for `R.drawable.ic_notification`
- `ui/src/main/res/drawable/tulkki_logo.xml` — the artwork as a **screen mark**, full
  colour on a transparent background, for the welcome/onboarding screens and the drawer
  header
- `ui/src/main/res/drawable/tulkki_logo_signet.xml` — the silhouette as a **screen
  mark**, tinted by `?colorControlNormal`, for the settings row
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`
- `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher{,_round}.webp`
  — legacy rasters, **`.webp`**, cut from the full icon, the round one masked to an
  inscribed circle
- `app/src/main/ic_launcher-playstore.png` at 512 px, copied byte for byte to the two
  store-listing icons, `fastlane/metadata/android/en-GB/icon.png` (the one F-Droid
  reads) and `fastlane/metadata/android/en-GB/images/icon.png` (the screenshot drop's
  own copy)

The store listing is **en-GB**: `fastlane/metadata/android/en-GB/` holds the title,
summary, description and both icons, and `fastlane/metadata/android/fi-FI/` holds the
title, summary and description of the Finnish listing. The whole upstream listing was
deleted — `en-US`, `de`, and the five translated locales `fr-FR`, `nl-NL`, `ru-RU`,
`tr-TR` and `zh-CN` — because every file in it named the old brand; the store banner and
the legacy phone screenshots went with it, and a future banner is generated from the
brand art rather than inherited. Recreating an `en-US` tree is not the remedy: F-Droid
documents `en-US` as the fallback language `fdroiddata` looks for, so an en-GB-only tree
may not be picked up at all — the owner's decision, not a gap to close.

The background is the chosen palette's flat colour, so changing the tile in
`tulkki-icon.svg` changes it everywhere: the adaptive background, the legacy
rasters, the Play image, and the seam that shows through between the hemispheres.

The adaptive layers are sized to the ink and centred on it; the legacy rasters are
rasterised from the whole 240-unit tile and are not affected by `INK_FRACTION`.
The two screen marks, `tulkki_logo` and `tulkki_logo_signet`, are a third case: they are
drawn in the artwork's **own 240-unit canvas, one unit to one dp**, with their intrinsic
size set by `LOGO_DP` in `tools/icon.py`. `INK_FRACTION` does not reach them — a mark on
a screen is not a launcher layer under a mask — and a rename of either has to be carried
to every reference: `WelcomeScreen.kt` (twice), `MagicCreateScreen.kt` and `PickServerScreen.kt`
for `tulkki_logo`, `MainSettingsPage.kt` for `tulkki_logo_signet`.
The two are worth keeping an eye on, since they are sized by different rules: the
adaptive ink is 58.1 dp of the 108 dp layer, which a launcher draws as
`58.1 × 48/72 = 38.7 dp` in a 48 dp slot, while the legacy raster's ink is 75.0%
of its tile, i.e. 36.0 dp. Before `INK_FRACTION` existed the adaptive was 44.0 dp
— 22% larger than the legacy icon — and it is now 7.5% larger, so shrinking the
ink brought the two together rather than apart. Exact agreement would need
`INK_FRACTION = 0.818`, which over-shrinks for the mask.

`app/src/main/AndroidManifest.xml` uses `android:icon="@mipmap/ic_launcher"`, and the
adaptive icon references `@drawable/ic_launcher_monochrome`. Since the flavours were
collapsed to the single `tulkki` flavour there is nothing else that has to satisfy the
monochrome reference — but the adaptive-icon merger fails outright if the drawable goes
missing, so the file it points at must keep existing.

To change a palette, edit `identity/icon/tools/palette.py` and re-run it against the trace
rather than hand-editing the fills — but note the input it needs, above. To change
the artwork itself, edit all three SVGs together: the build measures `-art` and
paints the tile, the facets and the silhouette from the same coordinates.
