#!/usr/bin/env python3
"""
Render the trailer's Chinese subtitle strips.

qlmanage is the only SVG rasteriser on this Mac and it composites onto an opaque white page,
so transparency cannot survive the PNG. Instead every strip is drawn on a solid key colour
(pure green, which appears nowhere in the artwork) and assemble_trailer.py keys it out with
ffmpeg's colorkey before overlaying. The strip sits above the app's own English captions, so a
viewer gets the crew in English (burned in by the app) and the shot named in Chinese below it.
"""
import hashlib
import pathlib
import subprocess

HERE = pathlib.Path(__file__).resolve().parent
WORK = HERE / "subwork"
STRIP_H = 76
KEY = "#00FF00"

SVG = """<svg xmlns='http://www.w3.org/2000/svg' width='1280' height='1280' viewBox='0 0 1280 1280'>
<rect width='1280' height='1280' fill='{key}'/>
<rect x='{x}' y='600' width='{w}' height='{h}' rx='10' fill='#120A16'/>
<rect x='{x}' y='{uy}' width='{w}' height='2' fill='#FFCF8C' opacity='0.5'/>
<text x='640' y='{ty}' text-anchor='middle' font-family="'PingFang SC','Hiragino Sans GB','STHeiti',sans-serif"
 font-size='{fs}' fill='#FFF4E8' letter-spacing='2'>{text}</text>
</svg>
"""


def strip(text):
    """Path to the 1280x{STRIP_H} keyed PNG for one Chinese line (cached by content)."""
    WORK.mkdir(exist_ok=True)
    key = hashlib.md5(text.encode()).hexdigest()[:10]
    out = WORK / f"sub_{key}.png"
    if out.exists():
        return out
    fs = 38 if len(text) <= 18 else 33
    w = min(1180, int(len(text) * fs * 1.06) + 72)
    svg = WORK / f"sub_{key}.svg"
    svg.write_text(SVG.format(key=KEY, x=640 - w // 2, w=w, h=STRIP_H, uy=600 + STRIP_H - 2,
                              ty=600 + STRIP_H // 2 + fs // 3, fs=fs, text=text), encoding="utf-8")
    subprocess.run(["qlmanage", "-t", "-s", "1280", "-o", str(WORK), str(svg)], check=True, capture_output=True)
    png = WORK / f"{svg.name}.png"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(png),
                    "-vf", f"crop=1280:{STRIP_H}:0:600", "-frames:v", "1", str(out)], check=True)
    return out


if __name__ == "__main__":
    print("sample strip:", strip("驱动蛋白，扛着货囊一步步行走"))
