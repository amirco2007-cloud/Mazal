"""Generate icon-192.png and icon-512.png from a flat design using Pillow.

Run from the project root:
    python tools/build-icons.py

No external SVG renderer required — Pillow only.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

OUT_DIR = Path(__file__).resolve().parent.parent / "icons"
OUT_DIR.mkdir(parents=True, exist_ok=True)

BG = (155, 44, 44)        # #9B2C2C
INNER = (123, 31, 31)     # #7B1F1F
ACCENT = (236, 201, 75)   # #ECC94B


def find_font(size):
    candidates = [
        "C:/Windows/Fonts/arialbd.ttf",
        "C:/Windows/Fonts/arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    ]
    for path in candidates:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def render(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), BG)
    draw = ImageDraw.Draw(img)
    r = int(size * 0.36)
    cx = cy = size // 2
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=INNER)

    font = find_font(int(size * 0.55))
    text = "?"
    bbox = draw.textbbox((0, 0), text, font=font)
    w = bbox[2] - bbox[0]
    h = bbox[3] - bbox[1]
    x = (size - w) // 2 - bbox[0]
    y = (size - h) // 2 - bbox[1]
    draw.text((x, y), text, fill=ACCENT, font=font)
    return img


def main():
    for size in (192, 512):
        out = OUT_DIR / f"icon-{size}.png"
        render(size).save(out, "PNG")
        print(f"wrote {out}")


if __name__ == "__main__":
    main()
