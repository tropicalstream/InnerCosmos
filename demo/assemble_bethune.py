#!/usr/bin/env python3
"""
Cut the Chapter III (Bethune) trailer from the scrcpy capture of the glasses.

Same pipeline as assemble_trailer.py — left eye, 2x, device audio, Chinese subtitle strips over
the app's own English captions, bilingual title cards, drone bed ducked under the crew — but a
shorter cut for the one chapter.

    ./capture_bethune.sh                 # record chapter III from the glasses
    python3 make_cards.py b_title ...    # the bilingual cards
    python3 assemble_bethune.py
"""
import pathlib
import subprocess

import make_subs

OUT = pathlib.Path(__file__).resolve().parent
SCRATCH = pathlib.Path("/private/tmp/claude-501/-Users-me-Downloads/b3a19895-caad-4d70-9120-cc627029e290/scratchpad")
RAW = SCRATCH / "raw_bethune_fixed.mkv"
MARKS = SCRATCH / "bethune_marks.txt"
DRONE = SCRATCH / "drone.wav"
WORK = SCRATCH / "cutwork_beth"
FINAL = OUT / "InnerCosmos_Bethune_trailer.mp4"
SUB_Y = 724

# ---------------------------------------------------------------- speech-aware cuts
# A trailer that starts and stops mid-sentence is unwatchable, so every cut is snapped to the
# silences in the captured audio: the shot begins where the crew START a line and ends where one
# FINISHES. Boundaries come from ffmpeg's silencedetect over the whole capture, computed once.
_silences = None


def silences():
    """[(start, end)] of every quiet stretch in the capture, in seconds."""
    global _silences
    if _silences is None:
        out = subprocess.run(
            ["ffmpeg", "-hide_banner", "-nostats", "-i", str(RAW), "-af",
             "silencedetect=noise=-34dB:d=0.45", "-f", "null", "-"],
            capture_output=True, text=True).stderr
        starts, spans = [], []
        for line in out.splitlines():
            if "silence_start:" in line:
                starts.append(float(line.split("silence_start:")[1].split()[0]))
            elif "silence_end:" in line and starts:
                spans.append((starts.pop(), float(line.split("silence_end:")[1].split()[0])))
        _silences = sorted(spans)
        print(f"  {len(_silences)} silences found in the capture")
    return _silences


def snap(start, dur, lead=0.35, max_stretch=7.0):
    """Move a cut's start to the beginning of the next line and its end to the end of a line."""
    sil = silences()
    if not sil:
        return start, dur
    # start: the last quiet stretch that ends within a few seconds before the intended point,
    # so the shot opens just as somebody starts speaking.
    begins = [e for _, e in sil if start - max_stretch <= e <= start + max_stretch]
    new_start = (max(begins) if begins else start) - lead
    # end: the first quiet stretch that starts at or after the intended out point.
    target = new_start + dur
    ends = [b for b, _ in sil if target - 1.0 <= b <= target + max_stretch]
    new_end = (min(ends) if ends else target) + lead
    return max(0.0, new_start), max(3.0, new_end - new_start)


marks = {}
for line in MARKS.read_text().splitlines():
    t, _, label = line.strip().partition(" ")
    marks[label] = float(t)


def m(label, plus=0.0):
    return marks[label] + plus


# (kind, arg, seconds, chinese subtitle)
CUTS = [
    ("card", "b_title", None, None),
    ("cut", m("depth menu", 1.5), 4.0, "三章可选，今晚进入白求恩"),
    ("card", "b_turn", None, None),
    ("cut", m("cavity", 22.0), 11.0, "前方那片黑暗，是结核在肺里蚀出的空洞"),
    ("cut", m("cavity bridge", 6.0), 10.0, "四周都是肺泡，壁比肥皂泡还薄"),
    ("card", "b_spain", None, None),
    ("cut", m("vein chase", 6.0), 10.0, "静脉里，针头正把血抽走"),
    ("cut", m("bottle chase", 6.0), 9.0, "库存血：红细胞沉底，血浆在上，泛着冷蓝"),
    ("card", "b_china", None, None),
    ("cut", m("front bridge", 7.0), 11.0, "撕裂的伤口；旁边的延安会面是后人画的"),
    ("cut", m("front chase (yanan plate)", 7.0), 10.0, None),
    ("cut", m("transfusion chase", 6.0), 9.0, "输血送到，缺氧的组织重新有了颜色"),
    ("cut", m("table chase", 6.0), 10.0, "缝合得当：伤口边缘干净、红润"),
    ("card", "b_students", None, None),
    ("cut", m("students chase", 6.0), 10.0, "骨髓自己不运氧，却造出运氧的细胞"),
    ("cut", m("cut bridge", 6.0), 9.0, "中指上的小口子，细菌就是从这里进去的"),
    ("cut", m("fever chase", 6.0), 10.0, "败血症：细菌在血里分裂，白细胞顶不住"),
    ("cut", m("memory deck (essay, bai qiuen)", 8.0), 12.0, "白求恩的画像悬在细胞之间，回头看一眼"),
    ("card", "b_life", None, None),
    ("cut", m("coda numbers (life expectancy)", 4.0), 12.0, "延长寿命的是干净的水、疫苗和无数无名的人"),
    ("cut", m("coda circle closes", 6.0), 14.0, None),
    ("card", "b_end", None, None),
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
        arg, dur = snap(arg, dur)
        vf = "crop=640:480:0:0,scale=1280:960:flags=lanczos,format=yuv420p"
        cmd = ["ffmpeg", "-v", "error", "-y", "-ss", f"{arg:.2f}", "-i", RAW]
        if zh:
            cmd += ["-loop", "1", "-framerate", "30", "-t", f"{dur:.2f}", "-i", str(make_subs.strip(zh)),
                    "-filter_complex",
                    f"[0:v]{vf}[v];[1:v]colorkey=0x00FF00:0.35:0.12,format=rgba,fade=t=in:st=0:d=0.4:alpha=1,"
                    f"fade=t=out:st={dur - 0.5:.2f}:d=0.4:alpha=1[s];[v][s]overlay=0:{SUB_Y}:format=auto[vo]",
                    "-map", "[vo]", "-map", "0:a"]
        else:
            cmd += ["-vf", vf]
        cmd += ["-t", f"{dur:.2f}", "-r", "30", "-c:v", "libx264", "-preset", "fast", "-crf", "18",
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
        "[1:a]atrim=0:{d:.2f},volume=0.85,afade=t=out:st={fo:.2f}:d=3[bed];"
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
