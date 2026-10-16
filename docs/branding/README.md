# Branding

JPDFium uses the "press crafted" mark: an origami document press. A single flat
sheet slides into an intake integrated into the top face, and emerges as one
continuous, gently draping output sheet. The output sheet uses a continuous curve
generated from a beam-sag model.

## Assets

| File | Use |
|---|---|
| `../images/jpdfium-logo.svg` / `.png` | The mark cropped to its bounds. README and documentation headers. |
| `../images/jpdfium-avatar.svg` / `.png` | The same mark in a square canvas. Repository avatar and small square placements. |
| `../images/jpdfium-lockup.svg` / `.png` | Mark with the wordmark, 1560x512. Preferred lockup. |
| `../images/jpdfium-hero.svg` / `.png` | Light banner, 1850x1000. Optional, for presentations and social cards. |

The SVG files are the vector sources and the lockup and hero carry the wordmark.
The PNGs are rasterized from them for screen use.

## Regenerating

The assets are reproducible for fixed versions of `numpy`, `scipy`,
`matplotlib`, `cairosvg`, and DejaVu Sans: the SVG and ZIP text is stable, while
the PNG bytes come from `cairosvg` and the sag curve from `scipy`. Regenerate
them with:

```bash
pip install numpy scipy matplotlib cairosvg
python docs/branding/jpdfium_logo.py docs/images
```

The script also writes `jpdfium-brand-source.zip` for distribution. The wordmark
uses DejaVu Sans Bold, so the rasterized text matches on any host that has it.
