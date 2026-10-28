"""Render the existing Android Echo vector as Windows/window icons (requires Pillow)."""

from pathlib import Path
import re
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
PNG = ROOT / "desktop/src/main/resources/echo-icon.png"
ICO = ROOT / "desktop/resources/echo.ico"
SIZES = (16, 24, 32, 48, 64, 128, 256)


def generate():
    vector = ET.parse(ROOT / "app/src/main/res/drawable/ic_echo.xml").getroot()
    width = float(vector.get(ANDROID + "viewportWidth"))
    height = float(vector.get(ANDROID + "viewportHeight"))
    assert width == height and width > 0, "Echo icon must have a square viewport"
    background, bars = list(vector)
    assert background.tag == bars.tag == "path"
    assert background.get(ANDROID + "pathData") == f"M0,0h{width:g}v{height:g}h-{width:g}z", "Unsupported Android background path"
    assert bars.get(ANDROID + "strokeLineCap") == "round"
    path = bars.get(ANDROID + "pathData")
    segments = re.findall(r"M([0-9.]+),([0-9.]+)v([0-9.]+)", path)
    assert segments and "".join(f"M{x},{y}v{delta}" for x, y, delta in segments) == path, "Unsupported Android icon path; update this renderer"
    background_color = background.get(ANDROID + "fillColor")
    stroke_color = bars.get(ANDROID + "strokeColor")
    stroke = float(bars.get(ANDROID + "strokeWidth"))
    assert stroke > 0
    resolution = 512
    supersampling = 4
    scale = resolution * supersampling / width
    image = Image.new("RGBA", (resolution * supersampling,) * 2, background_color)
    draw = ImageDraw.Draw(image)
    radius = stroke * scale / 2
    for x, y, delta in segments:
        x, y, delta = float(x), float(y), float(delta)
        assert stroke / 2 <= x <= width - stroke / 2 and y >= stroke / 2 and y + delta <= height - stroke / 2
        draw.rounded_rectangle((x * scale - radius, y * scale - radius,
                                x * scale + radius, (y + delta) * scale + radius),
                               radius=radius, fill=stroke_color)
    image = image.resize((resolution,) * 2, Image.Resampling.LANCZOS)
    PNG.parent.mkdir(parents=True, exist_ok=True)
    ICO.parent.mkdir(parents=True, exist_ok=True)
    image.save(PNG)
    image.save(ICO, sizes=[(size, size) for size in SIZES])
    with Image.open(PNG) as saved:
        assert saved.size == (resolution,) * 2
        assert saved.getpixel((0, 0)) == Image.new("RGBA", (1, 1), background_color).getpixel((0, 0))
        for x, y, delta in segments:
            center = (round(float(x) * resolution / width),
                      round((float(y) + float(delta) / 2) * resolution / height))
            assert saved.getpixel(center) == Image.new("RGBA", (1, 1), stroke_color).getpixel((0, 0))
    with Image.open(ICO) as saved:
        assert saved.ico.sizes() == {(size, size) for size in SIZES}
        for size in SIZES:
            assert saved.ico.getimage((size, size)).size == (size, size)
    print("Generated Android-matching PNG and ICO; all Windows sizes and source colors verified")


if __name__ == "__main__":
    generate()
