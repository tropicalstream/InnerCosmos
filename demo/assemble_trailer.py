#!/usr/bin/env python3
"""
Cut the InnerCosmos trailer from raw_trailer.mkv (scrcpy: 1280x480 side-by-side + device audio).

Pipeline: for each cut, take the LEFT eye (crop 640x480), scale 2x to 1280x960, with the device
audio; interleave title cards; concatenate; then mix the synthesized organ-drone bed underneath
(ducked while the crew speaks, via sidechain compression) and fade the whole thing in/out.

Cut times are expressed relative to capture marks (capture_marks.txt: "<seconds> <label>").
"""
import json
import pathlib
import subprocess
import sys

OUT = pathlib.Path(__file__).resolve().parent
RAW = OUT / "raw_fixed.mkv"
MARKS = OUT / "capture_marks.txt"
DRONE = OUT / "drone.wav"
WORK = OUT / "cutwork"
FINAL = OUT / "InnerCosmos_trailer.mp4"

marks = {}
for line in MARKS.read_text().splitlines():
    t, _, label = line.strip().partition(" ")
    marks[label] = float(t)


def m(label, plus=0.0):
    return marks[label] + plus


# (kind, arg, duration) — kind "card" uses card_<arg>.mp4; kind "cut" uses (start seconds) in the raw file.
CUTS = [
    ("card", "title", None),
    ("cut", m("launch (splash)", 4.0), 5.0),                        # the title card on the glasses, rings contracting
    ("cut", m("menu", 0.5), 3.5),                                    # the depth menu
    ("card", "tour", None),
    ("cut", m("seg0 threshold intro (script t=0)", 2.0), 10.0),      # welcome aboard (bridge)
    ("cut", m("seg0 threshold intro (script t=0)", 27.0), 10.0),     # Doc: the face, melanin (external)
    ("cut", m("seg0 threshold intro (script t=0)", 123.0), 22.0),    # countdown, the three-decade drop, the cave mouth
    ("card", "scale", None),
    ("cut", m("seg3 bloodstream jump", 2.0), 12.0),                  # scale jump streaks
    ("cut", m("seg3 view chase", 3.0), 10.0),                        # red cells single file
    ("cut", m("seg4 view chase", 4.0), 12.0),                        # the valve slams
    ("cut", m("seg4 view bridge", 3.0), 8.0),
    ("cut", m("seg5 view chase", 6.0), 16.0),                        # the neutrophil chase
    ("cut", m("seg6 view bridge", 4.0), 8.0),                        # the axon
    ("cut", m("seg6 view chase", 4.0), 8.0),
    ("cut", m("seg7 view chase (arms)", 6.0), 12.0),                 # arm probes at the membrane
    ("card", "science", None),
    ("cut", m("seg9 view chase (helix)", 4.0), 12.0),                # the double helix
    ("cut", m("seg9 view deck", 3.0), 6.0),
    ("cut", m("seg11 atom jump", 3.0), 8.0),                         # the three-stage drop
    ("cut", m("seg11 view deck", 4.0), 12.0),                        # the electron cloud
    ("card", "family", None),
    ("cut", m("seg12 view chase (expansion)", 2.0), 18.0),           # the re-expansion
    ("cut", m("seg12 view deck (finale)", 10.0), 24.0),              # the finale
    ("card", "end", None),
]


def run(cmd):
    print(" ".join(str(c) for c in cmd)[:220])
    subprocess.run([str(c) for c in cmd], check=True)


def main():
    WORK.mkdir(exist_ok=True)
    parts = []
    for i, (kind, arg, dur) in enumerate(CUTS):
        if kind == "card":
            parts.append(OUT / f"card_{arg}.mp4")
            continue
        p = WORK / f"cut_{i:02d}.mp4"
        run(["ffmpeg", "-v", "error", "-y", "-ss", f"{arg:.2f}", "-i", RAW, "-t", f"{dur:.2f}",
             "-vf", "crop=640:480:0:0,scale=1280:960:flags=lanczos,format=yuv420p",
             "-r", "30", "-c:v", "libx264", "-preset", "fast", "-crf", "18",
             "-af", "aresample=48000,volume=6dB", "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2", p])
        parts.append(p)
    lst = WORK / "concat.txt"
    lst.write_text("".join(f"file '{p}'\n" for p in parts))
    joined = WORK / "joined.mp4"
    run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", joined])
    dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", joined]).decode().strip())
    fo = max(0.0, dur - 3.0)
    # Music bed under the footage, ducked by the device audio (crew + cues), master fades.
    filt = (
        "[1:a]atrim=0:{d:.2f},volume=0.9,afade=t=out:st={fo:.2f}:d=3[bed];"
        "[0:a]volume=1.0,asplit=2[voice][key];"
        "[bed][key]sidechaincompress=threshold=0.03:ratio=6:attack=40:release=900:makeup=1[ducked];"
        "[voice][ducked]amix=inputs=2:duration=first:dropout_transition=2:normalize=0,alimiter=limit=0.95,afade=t=in:d=1.5,afade=t=out:st={fo:.2f}:d=3[a]"
    ).format(d=dur, fo=fo)
    run(["ffmpeg", "-v", "error", "-y", "-i", joined, "-i", DRONE, "-filter_complex", filt,
         "-map", "0:v", "-map", "[a]", "-vf", f"fade=t=in:d=1,fade=t=out:st={fo:.2f}:d=3",
         "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
         "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", FINAL])
    print("final:", FINAL, f"{dur:.1f}s")


if __name__ == "__main__":
    main()
