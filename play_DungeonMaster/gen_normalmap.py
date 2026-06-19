#!/usr/bin/env python3
"""Derive a tangent-space normal map from a diffuse texture.

Used for the DM walls (Step 3 of NORMAL_MAPPING.md). The normal map is DERIVED
from the diffuse so its relief (recessed mortar, block edges) lines up exactly
with what is painted in the diffuse.

Key idea: the diffuse often has BAKED lighting (test.png is brighter at the top,
darker at the bottom). Feeding raw luminance into a height map would tilt the
whole surface. So we high-pass it: height = luminance - blur(luminance, big),
which keeps only LOCAL detail (mortar lines, surface grain) and drops the global
gradient. Mortar (locally darker) -> negative height -> recessed groove.

Output: tangent-space normal map, OpenGL green convention (+Y up), to be used in
.bb with `TextureBlend normalTex, 4` (BLEND_DOT3). BLEND_DOT3 is consumed by the
fragment shader (commit 050c1fa) to perturb the lighting normal per-pixel.
"""
import sys
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter


def make_normal(src, dst, lowpass=48.0, denoise=1.5, strength=6.0,
                groove_boost=0.0, groove_margin=0.06, invert_green=False):
    L = np.asarray(Image.open(src).convert("L"), np.float32) / 255.0

    # 1. high-pass: remove baked lighting / large-scale shading, keep local detail
    height = L - gaussian_filter(L, lowpass)

    # 1b. deepen ONLY the black mortar borders between bricks (not the surface
    # grain). 'mortar' = how much DARKER a pixel is than its local neighbourhood,
    # beyond a margin: this isolates the continuous dark joint lines and ignores
    # the gentle grain on the block faces. We push those lines further down so
    # the joints read as deeper grooves while everything else keeps its strength.
    if groove_boost > 0.0:
        local = gaussian_filter(L, 6.0)
        mortar = np.clip(local - L - groove_margin, 0.0, None)
        mortar = gaussian_filter(mortar, 1.2)      # sloped groove walls, not a cliff
        height = height - mortar * groove_boost

    # 2. light denoise so surface grain doesn't become noisy normals
    height = gaussian_filter(height, denoise)

    # 3. gradients -> tangent-space normal (flat = +Z)
    gy, gx = np.gradient(height)
    nx = -gx * strength
    ny = -gy * strength
    nz = np.ones_like(height)
    inv = 1.0 / np.sqrt(nx * nx + ny * ny + nz * nz)
    nx *= inv; ny *= inv; nz *= inv

    # 4. encode. Image rows increase downward; OpenGL normal maps want +Y up, so
    # negate ny by default. invert_green flips this if the result looks inflated.
    gy_sign = +1.0 if invert_green else -1.0
    rgb = np.stack([nx * 0.5 + 0.5,
                    gy_sign * ny * 0.5 + 0.5,
                    nz * 0.5 + 0.5], axis=-1)
    Image.fromarray((np.clip(rgb, 0, 1) * 255).astype(np.uint8)).save(dst)
    print(f"wrote {dst}  ({L.shape[1]}x{L.shape[0]}, strength={strength}, lowpass={lowpass})")


if __name__ == "__main__":
    # default: the DM wall texture
    src = sys.argv[1] if len(sys.argv) > 1 else "test.png"
    dst = sys.argv[2] if len(sys.argv) > 2 else "test_n.png"
    make_normal(src, dst)
