"""Generate MeetingNotesApp launcher icons for all mipmap densities."""
import math
from pathlib import Path

from PIL import Image, ImageDraw

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

RES_DIR = Path(__file__).resolve().parent / "app" / "src" / "main" / "res"


def draw_icon(size: int) -> Image.Image:
    """Draw the MeetingNotes app icon at the given pixel size."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    s = size / 48.0  # scale factor (base design at 48dp)

    # --- Background: rounded square with gradient-like effect ---
    margin = int(2 * s)
    radius = int(10 * s)
    # Draw rounded rect background (deep indigo)
    _rounded_rect(draw, margin, margin, size - margin, size - margin, radius,
                   fill_top=(30, 60, 180), fill_bottom=(15, 30, 100))

    # --- Foreground: microphone + document lines ---
    cx, cy = size / 2, size / 2

    # Microphone body (rounded rect, centered)
    mic_w = int(8 * s)
    mic_h = int(14 * s)
    mic_x1 = int(cx - mic_w / 2)
    mic_y1 = int(cy - mic_h / 2 - 4 * s)
    mic_x2 = int(cx + mic_w / 2)
    mic_y2 = int(cy + mic_h / 2 - 4 * s)
    mic_r = int(4 * s)
    _rounded_rect(draw, mic_x1, mic_y1, mic_x2, mic_y2, mic_r,
                   fill_top=(255, 255, 255), fill_bottom=(230, 240, 255))

    # Microphone arc (bottom)
    arc_x1 = int(cx - 9 * s)
    arc_y1 = int(cy - 2 * s)
    arc_x2 = int(cx + 9 * s)
    arc_y2 = int(cy + 10 * s)
    draw.arc([arc_x1, arc_y1, arc_x2, arc_y2], 0, 180,
             fill=(255, 255, 255), width=max(int(2 * s), 1))

    # Microphone stand (vertical line)
    stand_x = int(cx)
    stand_y1 = int(cy + 4 * s)
    stand_y2 = int(cy + 9 * s)
    draw.line([(stand_x, stand_y1), (stand_x, stand_y2)],
              fill=(255, 255, 255), width=max(int(2 * s), 1))

    # Document lines (to the right of mic)
    line_x1 = int(cx + 7 * s)
    line_x2 = int(cx + 16 * s)
    line_colors = [(200, 230, 255), (180, 215, 255), (160, 200, 255)]
    for i, color in enumerate(line_colors):
        ly = int(cy - 6 * s + i * 5 * s)
        lw = line_x2 - int(i * 3 * s)
        draw.rounded_rectangle([line_x1, ly, lw, ly + max(int(2.5 * s), 1)],
                               radius=max(int(1 * s), 1), fill=color)

    # Small dots on lines (like bullet points)
    for i in range(3):
        dy = int(cy - 6 * s + i * 5 * s)
        dx = int(cx + 5 * s)
        dot_r = max(int(1 * s), 1)
        draw.ellipse([dx - dot_r, dy - dot_r + 1, dx + dot_r, dy + dot_r + 1],
                     fill=(255, 220, 100))

    return img


def draw_round_icon(size: int) -> Image.Image:
    """Draw the round version (circle clip)."""
    icon = draw_icon(size)
    mask = Image.new("L", (size, size), 0)
    mdraw = ImageDraw.Draw(mask)
    mdraw.ellipse([0, 0, size - 1, size - 1], fill=255)
    icon.putalpha(mask)
    return icon


def _rounded_rect(draw, x1, y1, x2, y2, r, fill_top=None, fill_bottom=None):
    """Draw a rounded rectangle with a vertical gradient."""
    if fill_top is None:
        fill_top = (30, 60, 180)
    if fill_bottom is None:
        fill_bottom = fill_top

    # Create gradient
    for y in range(y1, y2 + 1):
        t = (y - y1) / max(y2 - y1, 1)
        color = tuple(int(fill_top[i] + (fill_bottom[i] - fill_top[i]) * t) for i in range(3))
        # Clip to rounded rect shape
        if y1 + r <= y <= y2 - r:
            draw.line([(x1, y), (x2, y)], fill=color)
        elif y < y1 + r:
            # Top rounded corner
            dy = (y1 + r) - y
            dx = int(r - math.sqrt(max(r * r - dy * dy, 0)))
            draw.line([(x1 + dx, y), (x2 - dx, y)], fill=color)
        else:
            # Bottom rounded corner
            dy = y - (y2 - r)
            dx = int(r - math.sqrt(max(r * r - dy * dy, 0)))
            draw.line([(x1 + dx, y), (x2 - dx, y)], fill=color)


def main():
    for density, size in DENSITIES.items():
        out_dir = f"{RES_DIR}/{density}"
        import os
        os.makedirs(out_dir, exist_ok=True)

        icon = draw_icon(size)
        icon.save(f"{out_dir}/ic_launcher.png")
        print(f"  {density}/ic_launcher.png  ({size}x{size})")

        round_icon = draw_round_icon(size)
        round_icon.save(f"{out_dir}/ic_launcher_round.png")
        print(f"  {density}/ic_launcher_round.png  ({size}x{size})")

    print("\nDone! All mipmap PNGs generated.")


if __name__ == "__main__":
    main()
