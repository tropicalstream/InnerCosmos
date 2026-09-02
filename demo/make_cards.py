#!/usr/bin/env python3
"""
Render the trailer's title cards: SVG -> PNG (qlmanage, the only SVG rasteriser on this Mac) ->
a still MP4 of the right length with silent audio, ready for the concat in assemble_trailer.py.

qlmanage always renders onto a SQUARE canvas, so every card is laid out 1280x1280 and cropped
to the trailer's 1280x960 (the band y=160..1120).

    python3 demo/make_cards.py            # all cards
    python3 demo/make_cards.py title end  # just these
"""
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
PNG = HERE / "cardpng"

# card -> seconds on screen (long enough to read both languages, never long enough to drag)
DURATIONS = {
    "title": 4.5,
    "two": 3.6,
    "descent": 4.0,
    "scale": 3.6,
    "machine": 4.0,
    "science": 4.0,
    "family": 5.5,
    "end": 6.0,
    # Chapter III (Bethune)
    "b_title": 4.8,
    "b_turn": 4.6,
    "b_spain": 4.4,
    "b_china": 4.4,
    "b_students": 4.8,
    "b_life": 4.8,
    "b_end": 6.0,
}


def run(cmd):
    subprocess.run([str(c) for c in cmd], check=True, capture_output=True)


def main():
    PNG.mkdir(exist_ok=True)
    names = sys.argv[1:] or sorted(DURATIONS)
    for name in names:
        svg = HERE / f"card_{name}.svg"
        if not svg.exists():
            print(f"  ? no {svg.name}, skipping")
            continue
        png = PNG / f"card_{name}.svg.png"
        png.unlink(missing_ok=True)
        run(["qlmanage", "-t", "-s", "1280", "-o", PNG, svg])
        mp4 = HERE / f"card_{name}.mp4"
        dur = DURATIONS.get(name, 4.0)
        # A still frame with a matching stretch of silence, cut to the trailer's format so the
        # concat demuxer can join cards and footage without re-encoding.
        run(["ffmpeg", "-v", "error", "-y", "-loop", "1", "-t", f"{dur}", "-i", png,
             "-f", "lavfi", "-t", f"{dur}", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
             "-vf", "crop=1280:960:0:160,format=yuv420p",
             "-r", "30", "-c:v", "libx264", "-preset", "medium", "-crf", "18",
             "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2", "-shortest", mp4])
        print(f"  + {mp4.name}  {dur}s")


if __name__ == "__main__":
    main()
