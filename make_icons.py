"""Build Android launcher icons from the generated cosmic artwork icon-space.png.

The generator returns a rounded-square artwork on a white canvas. We crop the
white margins, turn the corners transparent, and produce:
  * legacy PNG icons (square + round) for mdpi..xxxhdpi
  * adaptive-icon background / foreground PNGs (Android 8+)
  * a 512px preview icon and a clean square artwork
"""
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
RES = HERE / "res"
SRC = HERE / "icon-space.png"
SCALE = 4  # supersampling for smooth masks

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def load_square_artwork():
    """Crop the white canvas away and return a square RGB image."""
    im = Image.open(SRC).convert("RGB")
    arr = np.array(im)
    nonwhite = arr.min(axis=2) < 245
    ys, xs = np.where(nonwhite)
    cx, cy = (xs.min() + xs.max()) // 2, (ys.min() + ys.max()) // 2
    side = max(xs.max() - xs.min() + 1, ys.max() - ys.min() + 1)
    side = min(side + 16, min(im.width, im.height))  # small safety padding
    half = side // 2
    left, top = cx - half, cy - half
    left = max(0, min(left, im.width - side))
    top = max(0, min(top, im.height - side))
    return im.crop((left, top, left + side, top + side)), (cx, cy, side)


def rounded_mask(size, radius_ratio=0.225):
    s = size * SCALE
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, s - 1, s - 1), radius=int(s * radius_ratio), fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def circle_mask(size):
    s = size * SCALE
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, s - 1, s - 1), fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def clean_white_halo(im):
    """Repaint the generator's white drop shadow / canvas as deep space.

    Only pixels far from the centre are touched, so the bright star at the apex
    and the glowing A stay untouched (a few outer stars may darken, unnoticeable).
    """
    arr = np.array(im.convert("RGB")).copy()
    h, w = arr.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    dist = np.sqrt(((xx - w / 2) / w) ** 2 + ((yy - h / 2) / h) ** 2)
    # The generator's white drop shadow hugs the outer 12% frame.
    frame = (xx < w * 0.12) | (xx > w * 0.88) | (yy < h * 0.12) | (yy > h * 0.88)
    pale = arr.min(axis=2) > 150
    repaint = pale & (frame | (dist > 0.33))
    arr[repaint] = (10, 12, 28)
    return Image.fromarray(arr, "RGB")


def make_legacy(art, size, round_icon=False):
    base = clean_white_halo(art.resize((size, size), Image.LANCZOS)).convert("RGBA")
    mask = circle_mask(size) if round_icon else rounded_mask(size)
    base.putalpha(mask)
    return base


def make_adaptive_background(art, size=432):
    """Full-bleed cosmic background with no white canvas or drop shadow."""
    return clean_white_halo(art.resize((size, size), Image.LANCZOS))


def make_adaptive_foreground(size=432):
    """Transparent layer: the artwork itself lives in the background drawable."""
    return Image.new("RGBA", (size, size), (0, 0, 0, 0))


def main():
    art, geom = load_square_artwork()
    print(f"artwork crop: center={geom[:2]} side={geom[2]} -> {art.size}")

    for folder, size in DENSITIES.items():
        out = RES / folder
        out.mkdir(parents=True, exist_ok=True)
        make_legacy(art, size, False).save(out / "ic_launcher.png")
        make_legacy(art, size, True).save(out / "ic_launcher_round.png")
        print(f"{folder}: {size}x{size}")

    drawable = RES / "drawable"
    drawable.mkdir(parents=True, exist_ok=True)
    old_vector = drawable / "ic_launcher_foreground.xml"
    if old_vector.exists():
        old_vector.unlink()  # same resource name, now a PNG
    make_adaptive_background(art).save(drawable / "ic_launcher_background.png")
    make_adaptive_foreground().save(drawable / "ic_launcher_foreground.png")
    print("adaptive background/foreground: 432x432")

    make_legacy(art, 512, False).save(HERE / "arena-icon-512.png")
    print("arena-icon-512.png: 512x512")

    # Clean square artwork (no white corners) for use as a standalone image.
    clean = make_adaptive_background(art, 1024)
    clean.save(HERE / "Arena-space-art.png")
    print("Arena-space-art.png: 1024x1024")


if __name__ == "__main__":
    main()
