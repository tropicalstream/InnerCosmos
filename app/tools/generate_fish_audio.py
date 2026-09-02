#!/usr/bin/env python3
"""
Pre-render EVERY line of a tour script (tour_script.json / tour2_script.json, --script) with Fish Audio (s2.1-pro), so the app
needs no API at runtime. Output: ../src/main/assets/voice/<role>/<clip>.ogg
(Opus in OGG via ffmpeg; --wav keeps the raw WAV instead).

Usage:
    cd app/tools
    cp fish_audio.config.example fish_audio.config   # then edit (key + 3 voice ids)
    python3 generate_fish_audio.py                    # renders only missing clips
    python3 generate_fish_audio.py --force            # re-render everything
    python3 generate_fish_audio.py --only finale      # clips whose name contains "finale"
    python3 generate_fish_audio.py --dry-run          # list what would be rendered

Config (fish_audio.config) holds API_KEY, MODEL, and one reference voice id per
role so the three crew members sound like different people.
Docs: https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech
"""
import sys
import json
import pathlib
import argparse
import shutil
import subprocess

try:
    import requests
except ImportError:
    sys.exit("Please run: pip install requests")

HERE = pathlib.Path(__file__).resolve().parent
APP = HERE.parent
SCRIPT = APP / "src" / "main" / "assets" / "tour_script.json"
VOICE = APP / "src" / "main" / "assets" / "voice"
CONFIG = HERE / "fish_audio.config"

API_URL = "https://api.fish.audio/v1/tts"
DEFAULT_MODEL = "s2.1-pro"

ROLE_FOLDER = {"NAVIGATION": "navigation", "SCIENCE": "science", "ENGINEERING": "engineering"}
ROLE_VOICE_KEY = {
    "NAVIGATION": "NAVIGATION_VOICE_ID",
    "SCIENCE": "SCIENCE_VOICE_ID",
    "ENGINEERING": "ENGINEERING_VOICE_ID",
}


def load_config():
    if not CONFIG.exists():
        sys.exit(f"Missing {CONFIG.name}. Copy fish_audio.config.example and fill it in.")
    cfg = {}
    for line in CONFIG.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        cfg[k.strip()] = v.strip()
    return cfg


def synth(cfg, text, voice_id, out_path):
    headers = {"Authorization": f"Bearer {cfg['API_KEY']}", "Content-Type": "application/json"}
    model = cfg.get("MODEL", DEFAULT_MODEL)
    if model:
        headers["model"] = model
    body = {"text": text, "format": "wav"}
    if voice_id:
        body["reference_id"] = voice_id
    try:
        r = requests.post(API_URL, headers=headers, data=json.dumps(body), timeout=180)
    except Exception as e:
        print(f"  ! {out_path.name}: {e}")
        return False
    if r.status_code == 400 and model and model != "s2-pro":
        # A backbone the account cannot use yet: fall back to s2-pro once, loudly.
        print(f"  ~ {out_path.name}: model '{model}' rejected ({r.text[:80]}); retrying with s2-pro")
        cfg["MODEL"] = "s2-pro"
        return synth(cfg, text, voice_id, out_path)
    if r.status_code != 200:
        print(f"  ! {out_path.name}: HTTP {r.status_code} {r.text[:160]}")
        return False
    out_path.parent.mkdir(parents=True, exist_ok=True)
    wav = out_path.with_suffix(".wav")
    wav.write_bytes(r.content)
    if out_path.suffix == ".ogg":
        # Compact Opus-in-OGG for the APK (~1/10 of the WAV); the app prefers .ogg, then .wav.
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(wav), "-c:a", "libopus", "-b:a", "64k", "-ac", "1", str(out_path)], check=True)
        wav.unlink()
    print(f"  + {out_path.relative_to(VOICE.parent)} ({out_path.stat().st_size} bytes)")
    return True


def main():
    global CONFIG
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--force", action="store_true", help="re-render clips that already exist")
    ap.add_argument("--only", default="", help="only clips whose name contains this substring")
    ap.add_argument("--dry-run", action="store_true", help="list the clips, do not call the API")
    ap.add_argument("--config", default=str(CONFIG), help="path to a fish_audio.config (default: alongside this script)")
    ap.add_argument("--model", default="", help="override the MODEL from the config (e.g. s2.1-pro)")
    ap.add_argument("--key-from", default="", help="read only the API key (API_KEY or FISH_API_KEY) from this other config file")
    ap.add_argument("--wav", action="store_true", help="keep uncompressed WAV clips instead of Opus/OGG (needs no ffmpeg)")
    ap.add_argument("--script", default=str(SCRIPT), help="the tour script to render (tour_script.json or tour2_script.json)")
    args = ap.parse_args()
    ext = "wav" if args.wav or not shutil.which("ffmpeg") else "ogg"
    CONFIG = pathlib.Path(args.config)
    script = pathlib.Path(args.script)

    if not script.exists():
        sys.exit(f"Missing script: {script}")
    data = json.loads(script.read_text())
    # Both timed dialog cues and silence-filler banter get rendered.
    items = [c for c in data.get("cues", []) if c.get("role") and c.get("clip") and c.get("text")]
    items += [f for f in data.get("fillers", []) if f.get("role") and f.get("clip") and f.get("text")]
    if args.only:
        items = [c for c in items if args.only in c["clip"]]

    todo = []
    for c in items:
        folder = ROLE_FOLDER.get(c["role"])
        if not folder:
            print(f"  ? unknown role {c['role']} ({c['clip']}), skipping")
            continue
        out = VOICE / folder / f"{c['clip']}.{ext}"
        other = VOICE / folder / f"{c['clip']}.{'wav' if ext == 'ogg' else 'ogg'}"
        if not args.force and any(f.exists() and f.stat().st_size > 1024 for f in (out, other)):
            continue
        todo.append((c, out))

    print(f"{len(items)} lines in script, {len(todo)} to render")
    if args.dry_run:
        for c, out in todo:
            words = len([w for w in c["text"].split() if not w.startswith("[")])
            print(f"  - {out.relative_to(VOICE.parent)}  ({words} words)")
        return

    cfg = load_config()
    if args.key_from:
        other = {}
        for line in pathlib.Path(args.key_from).read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                other[k.strip()] = v.strip()
        key = other.get("API_KEY") or other.get("FISH_API_KEY")
        if not key:
            sys.exit(f"No API_KEY / FISH_API_KEY in {args.key_from}")
        cfg["API_KEY"] = key
    if args.model:
        cfg["MODEL"] = args.model
    print(f"model: {cfg.get('MODEL', DEFAULT_MODEL)}  config: {CONFIG}")
    ok = 0
    for c, out in todo:
        voice = cfg.get(ROLE_VOICE_KEY[c["role"]], "")
        if synth(cfg, c["text"], voice, out):
            ok += 1
    print(f"\nDone: {ok}/{len(todo)} clips -> {VOICE}")


if __name__ == "__main__":
    main()
