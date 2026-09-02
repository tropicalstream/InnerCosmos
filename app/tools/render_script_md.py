#!/usr/bin/env python3
"""
Render SCRIPT.md (the human-readable ride script) from tour_script.json, so the
document and the app can never disagree. Run after editing the script:

    python3 app/tools/render_script_md.py
"""
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
# Usage: render_script_md.py [script.json] [out.md]  (defaults: tour I -> SCRIPT.md; pass tour2_script.json SCRIPT2.md for tour II)
SCRIPT = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else HERE.parent / "src" / "main" / "assets" / "tour_script.json"
OUT = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "SCRIPT.md"

VIEW_NAMES = {0: "BRIDGE (helm)", 1: "EXTERNAL (chase, the Mote)", 2: "SCALE DRIVE CORE", 3: "OBSERVATION DECK"}
ROLE_NAMES = {"NAVIGATION": "HELM", "SCIENCE": "DOC", "ENGINEERING": "ENGINEERING"}
NODE_NAMES = [
    "The Threshold", "The Airway", "The Alveolus", "The Bloodstream", "The Heart", "The Sentinel",
    "The Neuron", "The Membrane", "The Mitochondrion", "The Nucleus", "The Ribosome", "The Atom", "The Look Back",
]
if SCRIPT.name != "tour_script.json":
    # Other tours name their stops in the script's "segments" ("3.  THE PHAGE" -> "The Phage").
    try:
        _segs = json.loads(SCRIPT.read_text()).get("segments", [])
        _names = {}
        for _s in _segs:
            _label = _s["label"].split(".", 1)[-1].strip().title()
            _names[int(_s.get("node", len(_names)))] = _label
        if _names:
            NODE_NAMES = [_names.get(i, f"Stop {i + 1}") for i in range(max(_names) + 1)]
    except Exception:
        pass


def mmss(ms):
    s = ms // 1000
    return f"{s // 60:02d}:{s % 60:02d}"


def progress_at(keyframes, t):
    if t <= keyframes[0][0]:
        return keyframes[0][1]
    for (t0, p0), (t1, p1) in zip(keyframes, keyframes[1:]):
        if t0 <= t <= t1:
            return p0 + (p1 - p0) * ((t - t0) / (t1 - t0) if t1 > t0 else 0)
    return keyframes[-1][1]


def main():
    data = json.loads(SCRIPT.read_text())
    kf = data["keyframes"]
    cues = sorted(data["cues"], key=lambda c: c["t"])
    minutes = round(data["durationMs"] / 60000)
    out = []
    out.append(f"# InnerCosmos — Ride Script ({data.get('title', 'the descent')})\n")
    out.append(f"> A {minutes}-minute guided descent through the human body, one power of ten per stage, "
               "written on the foundations of science and Stephen Jay Gould's teaching that humankind is one "
               "biological family. All dialog is pre-recorded (Fish Audio S2-pro, with acting tags). "
               f"Generated from `app/src/main/assets/{SCRIPT.name}` by `app/tools/render_script_md.py`.\n")
    out.append("**Crew:** Helm (pilot: approaches, scale drops) · Doc (physiologist: wonder, facts, the finale) · "
               "Engineering (the scale drive, the immune drama)\n")
    out.append("**Views auto-shift on cue. Position runs The Threshold (0) to The Look Back (12). "
               "Silences over 10 s are filled with crew banter.**\n")
    out.append("\n---\n\n## Stops\n")
    out.append("| # | Stop | Menu start |\n| --- | --- | --- |")
    for seg in data.get("segments", []):
        out.append(f"| {seg['node'] + 1} | {seg['label']} | {mmss(seg['startMs'])} |")
    out.append("\n---\n\n## Timed script\n")
    last_node = -1
    for c in cues:
        node = int(progress_at(kf, c["t"]))
        node = max(0, min(node, len(NODE_NAMES) - 1))
        if node != last_node:
            out.append(f"\n### {node + 1}. {NODE_NAMES[node]}\n")
            last_node = node
        bits = [f"**{mmss(c['t'])}**"]
        if "view" in c:
            bits.append(f" · look: *{VIEW_NAMES.get(c['view'], c['view'])}*")
        if "sfx" in c:
            bits.append(f" · SFX: `{c['sfx']}`")
        out.append("".join(bits))
        if c.get("role") and c.get("text"):
            out.append(f"> **{ROLE_NAMES.get(c['role'], c['role'])}:** {c['text']}")
        out.append("")
    out.append("\n---\n\n## Filler banter (auto-plays during >10 s silences)\n")
    for f in data.get("fillers", []):
        out.append(f"- **{ROLE_NAMES.get(f['role'], f['role'])}:** {f['text']}")
    OUT.write_text("\n".join(out) + "\n")
    spoken = [c for c in cues if c.get("role")]
    print(f"Wrote {OUT} — {len(spoken)} lines, {len(data.get('fillers', []))} fillers, {minutes} min")


if __name__ == "__main__":
    main()
