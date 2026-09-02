#!/usr/bin/env python3
"""
PLACEHOLDER crew voices using macOS's built-in speech (`say`), for development and demos
when no Fish Audio key is available. Same inputs and outputs as generate_fish_audio.py
(reads tour_script.json, writes assets/voice/<role>/<clip>.ogg), so the app cannot tell
the difference — except that these are not the Fish Audio S2-pro voices. Re-run
generate_fish_audio.py --force with a valid key to replace them.

    python3 app/tools/generate_placeholder_voices.py            # renders only missing clips
    python3 app/tools/generate_placeholder_voices.py --force
"""
import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
APP = HERE.parent
SCRIPT = APP / "src" / "main" / "assets" / "tour_script.json"
VOICE = APP / "src" / "main" / "assets" / "voice"

ROLE_FOLDER = {"NAVIGATION": "navigation", "SCIENCE": "science", "ENGINEERING": "engineering"}
# Three distinct built-in voices; ~150 words per minute matches the script's timing model.
ROLE_VOICE = {"NAVIGATION": ("Daniel", 150), "SCIENCE": ("Samantha", 152), "ENGINEERING": ("Moira", 146)}


def clean(text):
    text = re.sub(r"\[[^\]]*\]", " ", text)          # drop Fish acting tags
    text = text.replace("—", ", ").replace("…", "...")
    return re.sub(r"\s+", " ", text).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()
    if not shutil.which("say") or not shutil.which("ffmpeg"):
        sys.exit("needs macOS `say` and ffmpeg")
    data = json.loads(SCRIPT.read_text())
    items = [c for c in data.get("cues", []) if c.get("role") and c.get("clip") and c.get("text")]
    items += [f for f in data.get("fillers", []) if f.get("role") and f.get("clip") and f.get("text")]
    ok = 0
    with tempfile.TemporaryDirectory() as tmp:
        for c in items:
            folder = ROLE_FOLDER.get(c["role"])
            if not folder:
                continue
            out = VOICE / folder / f"{c['clip']}.ogg"
            if not args.force and any((VOICE / folder / f"{c['clip']}.{e}").exists() for e in ("ogg", "wav")):
                continue
            voice, rate = ROLE_VOICE[c["role"]]
            aiff = pathlib.Path(tmp) / f"{c['clip']}.aiff"
            subprocess.run(["say", "-v", voice, "-r", str(rate), "-o", str(aiff), clean(c["text"])], check=True)
            out.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(aiff), "-ac", "1",
                            "-c:a", "libopus", "-b:a", "48k", str(out)], check=True)
            ok += 1
            print(f"  + {out.relative_to(VOICE.parent)}  [{voice}]")
    print(f"\nDone: {ok} placeholder clips -> {VOICE}")


if __name__ == "__main__":
    main()
