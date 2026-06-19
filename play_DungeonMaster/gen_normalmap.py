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
                groove_boost=0.0, groove_margin=0.06, groove_width=1.0,
                height_depth=0.0, invert_green=False):
    L = np.asarray(Image.open(src).convert("L"), np.float32) / 255.0

    # Two DECOUPLED relief layers, each with its own strength:
    #  - SURFACE grain (the gentle texture on the brick faces): high-pass of the
    #    luminance, scaled by `strength`. Lower it for smoother, less-rough bricks.
    #  - GROOVES (the black mortar joints between bricks): a mask of how much
    #    DARKER a pixel is than its local neighbourhood, scaled by `groove_boost`.
    #    Raise it to carve the borders deeper WITHOUT roughening the brick faces.
    surf = gaussian_filter(L - gaussian_filter(L, lowpass), denoise)
    sgy, sgx = np.gradient(surf)

    local = gaussian_filter(L, 6.0)
    mortar = np.clip(local - L - groove_margin, 0.0, None)   # >0 in the dark joints
    if groove_width > 1.0:
        # widen the joints: grey-dilate the mask (groove_width ~ extra px of width)
        from scipy.ndimage import grey_dilation
        s = max(2, int(round(groove_width)))
        mortar = grey_dilation(mortar, size=(s, s))
    mortar = gaussian_filter(mortar, 1.4)                    # sloped walls, not a cliff
    mgy, mgx = np.gradient(mortar)

    # combine: surface gradient * strength + groove gradient * groove_boost
    nx = -(sgx * strength + mgx * groove_boost)
    ny = -(sgy * strength + mgy * groove_boost)
    nz = np.ones_like(L)
    inv = 1.0 / np.sqrt(nx * nx + ny * ny + nz * nz)
    nx *= inv; ny *= inv; nz *= inv

    # encode normal. Image rows increase downward; OpenGL normal maps want +Y up,
    # so negate ny by default. invert_green flips this if the result looks inflated.
    gy_sign = +1.0 if invert_green else -1.0
    r = nx * 0.5 + 0.5
    g = gy_sign * ny * 0.5 + 0.5
    b = nz * 0.5 + 0.5

    # HEIGHT in alpha (for parallax-occlusion mapping): 1 = brick face, 0 = deepest
    # joint. Derived from the mortar mask so the parallax depth follows the joints.
    if height_depth > 0.0:
        h = 1.0 - np.clip(mortar / (mortar.max() + 1e-6) * height_depth, 0.0, 1.0)
        a = np.clip(h, 0.0, 1.0)
    else:
        a = np.ones_like(L)                                 # flat -> parallax no-op
    rgba = np.stack([r, g, b, a], axis=-1)
    Image.fromarray((np.clip(rgba, 0, 1) * 255).astype(np.uint8)).save(dst)
    print(f"wrote {dst}  ({L.shape[1]}x{L.shape[0]}, strength={strength}, groove={groove_boost}, height={height_depth})")


if __name__ == "__main__":
    # default: the DM wall texture
    src = sys.argv[1] if len(sys.argv) > 1 else "test.png"
    dst = sys.argv[2] if len(sys.argv) > 2 else "test_n.png"
    make_normal(src, dst)
