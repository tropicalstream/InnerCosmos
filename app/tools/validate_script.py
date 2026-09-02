#!/usr/bin/env python3
"""
Check a tour script against what the app can actually play:
sfx names the activity handles, view indices, node range, sorted cues,
segment ordering, unique clips and unique spoken text, and (optionally)
that every clip has a rendered voice file.

    python3 app/tools/validate_script.py app/src/main/assets/tour2_script.json
"""
import json, pathlib, sys

SFX = {"drive_engage","shrink","grow","lysis","heartbeat","klaxon","alarm","impact_soft","chime","spark","squelch"}
ROLES = {"NAVIGATION":"navigation","SCIENCE":"science","ENGINEERING":"engineering"}
HERE = pathlib.Path(__file__).resolve().parent
VOICE = HERE.parent / "src" / "main" / "assets" / "voice"


def main():
    path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else HERE.parent / "src/main/assets/tour_script.json")
    d = json.loads(path.read_text())
    bad = []
    cues, fills, segs = d["cues"], d.get("fillers", []), d.get("segments", [])
    kf = d["keyframes"]
    nodes = int(max(p for _, p in kf)) + 1
    if [t for t, _ in kf] != sorted(t for t, _ in kf): bad.append("keyframes not sorted by time")
    if kf[-1][0] > d["durationMs"]: bad.append("keyframes run past durationMs")
    if [c["t"] for c in cues] != sorted(c["t"] for c in cues): bad.append("cues not sorted by time")
    for c in cues:
        if c.get("sfx") and c["sfx"] not in SFX: bad.append(f"unknown sfx {c['sfx']} at {c['t']}")
        if "view" in c and not 0 <= c["view"] <= 3: bad.append(f"view out of range at {c['t']}")
        if c.get("role") and c["role"] not in ROLES: bad.append(f"unknown role {c['role']} at {c['t']}")
        if c.get("clip") and not c.get("text"): bad.append(f"clip without text at {c['t']}")
        if c["t"] > d["durationMs"]: bad.append(f"cue past the end at {c['t']}")
    if len(segs) != nodes: bad.append(f"{len(segs)} segments for {nodes} nodes")
    for i, s in enumerate(segs):
        if s.get("node", i) != i: bad.append(f"segment {i} claims node {s.get('node')}")
        if not 0 <= s["startMs"] < d["durationMs"]: bad.append(f"segment {i} starts outside the tour")
    if [s["startMs"] for s in segs] != sorted(s["startMs"] for s in segs): bad.append("segments not in order")
    spoken = [c for c in cues if c.get("clip")] + fills
    clips = [c["clip"] for c in spoken]
    dup = {c for c in clips if clips.count(c) > 1}
    if dup: bad.append(f"duplicate clip ids: {sorted(dup)}")
    texts = [" ".join(c["text"].split()) for c in spoken]
    dupt = {t for t in texts if texts.count(t) > 1}
    if dupt: bad.append(f"repeated dialog: {[t[:50] for t in dupt]}")
    missing = [c["clip"] for c in spoken
               if not any((VOICE / ROLES[c["role"]] / f"{c['clip']}.{e}").exists() for e in ("ogg", "wav"))]
    print(f"{path.name}: {len(cues)} cues ({len(clips)} spoken incl. {len(fills)} fillers), "
          f"{nodes} stops, {d['durationMs'] // 60000} min")
    if missing: print(f"  voices not yet rendered: {len(missing)}  e.g. {missing[:3]}")
    for b in bad: print("  ✗", b)
    print("  ✓ script is consistent with the app" if not bad else f"  {len(bad)} problem(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
