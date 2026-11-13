"""Package the shared Omni artwork as a multi-resolution Windows icon."""
from pathlib import Path
from PIL import Image

root = Path(__file__).parent.parent
source = root / 'public' / 'omni-logo.png'
if not source.exists():
    source = root / 'desktop' / 'public' / 'omni-logo.png'
image = Image.open(source).convert('RGBA').resize((256, 256), Image.Resampling.LANCZOS)
image.save(Path(__file__).with_name('icon.ico'), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
