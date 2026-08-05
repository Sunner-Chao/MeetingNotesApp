"""Generate legacy launcher PNGs from the project-owned brand master."""

from pathlib import Path

from PIL import Image, ImageDraw


DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

PROJECT_DIR = Path(__file__).resolve().parent
RES_DIR = PROJECT_DIR / "app" / "src" / "main" / "res"
MASTER_PATH = PROJECT_DIR / "app" / "src" / "main" / "icon" / "launcher-master.png"


def draw_icon(size: int) -> Image.Image:
    """Resize the current brand master for a legacy density fallback."""
    if not MASTER_PATH.is_file():
        raise FileNotFoundError(f"Launcher icon master not found: {MASTER_PATH}")
    with Image.open(MASTER_PATH) as master:
        return master.convert("RGBA").resize((size, size), Image.Resampling.LANCZOS)


def draw_round_icon(size: int) -> Image.Image:
    """Apply the legacy circular mask to the same brand artwork."""
    icon = draw_icon(size)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    icon.putalpha(mask)
    return icon


def main() -> None:
    for density, size in DENSITIES.items():
        output_dir = RES_DIR / density
        output_dir.mkdir(parents=True, exist_ok=True)

        draw_icon(size).save(output_dir / "ic_launcher.png")
        draw_round_icon(size).save(output_dir / "ic_launcher_round.png")
        print(f"Generated {density}: {size}x{size}")


if __name__ == "__main__":
    main()
