#!/usr/bin/env python3
"""
Cut the InnerCosmos trailer from the scrcpy capture (1280x480 side-by-side + device audio).

For each shot: take the LEFT eye (crop 640x480), scale 2x to 1280x960, keep the device audio,
and overlay a Chinese subtitle strip above the app's own English captions. Title cards are
bilingual (see make_cards.py). Everything is concatenated, then a synthesized organ-drone bed is
mixed underneath, ducked while the crew speaks, with fades top and tail.

    python3 demo/capture_trailer.sh      # record both tours from the glasses
    python3 demo/make_cards.py           # bilingual title cards
    python3 demo/assemble_trailer.py     # cut, subtitle, mix
"""
import pathlib
import subprocess

import make_subs

OUT = pathlib.Path(__file__).resolve().parent
SCRATCH = pathlib.Path("/private/tmp/claude-501/-Users-me-Downloads/b3a19895-caad-4d70-9120-cc627029e290/scratchpad")
RAW = SCRATCH / "raw_fixed2.mkv"
MARKS = SCRATCH / "capture_marks2.txt"
DRONE = SCRATCH / "drone.wav"
WORK = SCRATCH / "cutwork2"
FINAL = OUT / "InnerCosmos_trailer.mp4"
SUB_Y = 724          # the strip sits above the app's own caption box

marks = {}
for line in MARKS.read_text().splitlines():
    t, _, label = line.strip().partition(" ")
    marks[label] = float(t)


def m(label, plus=0.0):
    return marks[label] + plus


# (kind, arg, seconds, chinese subtitle)  — "card" uses card_<arg>.mp4; "cut" seeks into the raw file.
CUTS = [
    ("card", "title", None, None),
    ("cut", m("splash", 5.0), 5.0, "在 RayNeo X3 Pro AR 眼镜上运行"),
    ("cut", m("tour menu", 1.5), 4.0, "两段旅程，任选其一"),
    ("card", "two", None, None),

    # ------------------------------------------------------------ I. THE DESCENT
    ("card", "descent", None, None),
    ("cut", m("t1 threshold chase", 5.0), 10.0, "十二米长的探测艇，停在鼻孔外"),
    ("cut", m("t1 first drop window", 20.0), 14.0, "一次跃迁，缩小一千倍"),
    ("card", "scale", None, None),
    ("cut", m("t1 blood chase", 4.0), 10.0, "红细胞在血管中单列通过"),
    ("cut", m("t1 heart chase", 5.0), 11.0, "心脏每分钟推送五升血液"),
    ("cut", m("t1 sentinel chase", 6.0), 12.0, "中性粒细胞发现了我们"),
    ("cut", m("t1 nucleus chase", 5.0), 11.0, "DNA 双螺旋，生命的说明书"),
    ("cut", m("t1 atom deck", 5.0), 10.0, "碳原子内部，几乎空无一物"),
    ("cut", m("t1 expansion chase", 4.0), 12.0, "十二个数量级，回到人的尺度"),

    # ----------------------------------------------------- II. THE LIVING MACHINE
    ("card", "machine", None, None),
    ("cut", m("t2 mouth bridge", 5.0), 9.0, "从口腔进入，牙齿如同悬崖"),
    ("cut", m("t2 gut chase", 5.0), 10.0, "小肠绒毛，吸收面积三四十平方米"),
    ("cut", m("t2 phage chase", 5.0), 10.0, "噬菌体只猎杀细菌，不感染人体细胞"),
    ("cut", m("t2 muscle chase", 5.0), 9.0, "肌小节滑动，肌肉因此收缩"),
    ("cut", m("t2 shuffle chase", 5.0), 9.0, "进入细胞核：V(D)J 重组正在进行"),
    ("cut", m("t2 kinesin chase", 23.0), 14.0, "驱动蛋白，扛着货囊一步步行走"),
    ("cut", m("t2 division chase", 4.0), 11.0, "科学最好的一天，是发现自己错了的那天"),

    ("card", "science", None, None),
    ("cut", m("t2 motor deck", 6.0), 12.0, "你、我、每一个活过的人，零件都相同"),
    ("card", "family", None, None),
    # No Chinese line here: the card just before it already says "we are all human beings /
    # 我们都是人类", and the crew's own caption on this shot is a different thought.
    ("cut", m("t1 finale deck", 12.0), 14.0, None),
    ("card", "end", None, None),
]


def run(cmd):
    print(" ".join(str(c) for c in cmd)[:200])
    subprocess.run([str(c) for c in cmd], check=True)


def main():
    WORK.mkdir(exist_ok=True, parents=True)
    parts = []
    for i, (kind, arg, dur, zh) in enumerate(CUTS):
        if kind == "card":
            parts.append(OUT / f"card_{arg}.mp4")
            continue
        p = WORK / f"cut_{i:02d}.mp4"
        vf = "crop=640:480:0:0,scale=1280:960:flags=lanczos,format=yuv420p"
        cmd = ["ffmpeg", "-v", "error", "-y", "-ss", f"{arg:.2f}", "-i", RAW]
        if zh:
            # The strip is a still: loop it for the length of the shot so its fades can animate.
            cmd += ["-loop", "1", "-framerate", "30", "-t", f"{dur:.2f}", "-i", str(make_subs.strip(zh)),
                    "-filter_complex",
                    f"[0:v]{vf}[v];[1:v]colorkey=0x00FF00:0.35:0.12,format=rgba,fade=t=in:st=0:d=0.4:alpha=1,"
                    f"fade=t=out:st={dur - 0.5:.2f}:d=0.4:alpha=1[s];[v][s]overlay=0:{SUB_Y}:format=auto[vo]",
                    "-map", "[vo]", "-map", "0:a"]
        else:
            cmd += ["-vf", vf]
        # -t as an OUTPUT option: as an input option it would bind to the next -i instead.
        cmd += ["-t", f"{dur:.2f}",
                "-r", "30", "-c:v", "libx264", "-preset", "fast", "-crf", "18",
                "-af", "aresample=48000,volume=6dB", "-c:a", "aac", "-b:a", "160k",
                "-ar", "48000", "-ac", "2", str(p)]
        run(cmd)
        parts.append(p)

    lst = WORK / "concat.txt"
    lst.write_text("".join(f"file '{p}'\n" for p in parts))
    joined = WORK / "joined.mp4"
    run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", joined])
    dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                                         "-of", "csv=p=0", joined]).decode().strip())
    fo = max(0.0, dur - 3.0)
    filt = (
        "[1:a]atrim=0:{d:.2f},volume=0.9,afade=t=out:st={fo:.2f}:d=3[bed];"
        "[0:a]volume=1.0,asplit=2[voice][key];"
        "[bed][key]sidechaincompress=threshold=0.03:ratio=6:attack=40:release=900:makeup=1[ducked];"
        "[voice][ducked]amix=inputs=2:duration=first:dropout_transition=2:normalize=0,"
        "alimiter=limit=0.95,afade=t=in:d=1.5,afade=t=out:st={fo:.2f}:d=3[a]"
    ).format(d=dur, fo=fo)
    run(["ffmpeg", "-v", "error", "-y", "-i", joined, "-i", DRONE, "-filter_complex", filt,
         "-map", "0:v", "-map", "[a]", "-vf", f"fade=t=in:d=1,fade=t=out:st={fo:.2f}:d=3",
         "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
         "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", FINAL])
    print(f"final: {FINAL}  {dur:.1f}s")


if __name__ == "__main__":
    main()
