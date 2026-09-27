#!/usr/bin/env python3
"""
Voice expressiveness tools for the crew voices (ported from TapReader's CastDirector auditions).

  assess    rate the rendered clips of one role: measured pitch movement plus Gemini's ear
            (mechanical / expressive / follows the [bracket] cues), and list the flat ones
  audition  fish library candidates each perform contrasting lines from the role's own script,
            two takes each, rated blind in one shuffled batch; prints a ranked table

Keys are read at run time and never printed: fish from --fish-key-from (API_KEY or FISH_API_KEY),
Gemini from --gemini-key-file (a file holding just the key).

Pitch movement is the standard deviation of voiced f0 in semitones about the clip's median
(autocorrelation, 40 ms windows, 10 ms hop, 70-400 Hz). Lively speech moves about 3-5
semitones; under 2 is flat. Gemini's ear for "mechanical" varies between runs, so every
voice is judged on the average of two takes, and measurement and ear are used together.
"""
import argparse, base64, io, json, os, random, subprocess, sys, tempfile, time, urllib.request, urllib.error, wave
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "src", "main", "assets")
FISH_TTS = "https://api.fish.audio/v1/tts"
FISH_API = "https://api.fish.audio"
GEMINI = "https://generativelanguage.googleapis.com/v1beta/models/{}:generateContent"
GEMINI_MODELS = ["gemini-3.8-flash", "gemini-flash-latest", "gemini-3.5-flash", "gemini-2.5-flash"]


def read_key(path, names=("API_KEY", "FISH_API_KEY")):
    with open(os.path.expanduser(path)) as f:
        text = f.read()
    for line in text.splitlines():
        k, _, v = line.strip().partition("=")
        if k.strip() in names and v.strip():
            return v.strip().strip('"')
    return text.strip()                      # a bare key file


# ---------------------------------------------------------------- audio

def decode(data_or_path, rate=16000):
    """Any audio (bytes or path) -> mono float32 at [rate] via ffmpeg."""
    src = data_or_path if isinstance(data_or_path, str) else "pipe:0"
    p = subprocess.run(["ffmpeg", "-v", "error", "-i", src, "-ac", "1", "-ar", str(rate), "-f", "f32le", "pipe:1"],
                       input=None if isinstance(data_or_path, str) else data_or_path, capture_output=True, check=True)
    return np.frombuffer(p.stdout, dtype=np.float32), rate


def to_wav(data_or_path, rate=24000):
    x, _ = decode(data_or_path, rate)
    b = io.BytesIO()
    with wave.open(b, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    return b.getvalue()


def pitch_spread(x, sr):
    """(median f0 Hz, spread in semitones) of the voiced frames, or None."""
    win, hop = sr * 40 // 1000, sr // 100
    rms_all = float(np.sqrt(np.mean(x * x))) + 1e-9
    lo, hi = sr // 400, sr // 70
    f0 = []
    for s in range(0, len(x) - win, hop):
        seg = x[s:s + win] - x[s:s + win].mean()
        e = float(np.dot(seg, seg))
        if np.sqrt(e / win) < rms_all * 0.5:
            continue
        ac = np.correlate(seg, seg, "full")[win - 1:]
        lags = ac[lo:hi + 1]
        k = int(np.argmax(lags))
        if lags[k] > 0.35 * e:
            f0.append(sr / (lo + k))
    if len(f0) < 10:
        return None
    f0 = np.array(f0); med = float(np.median(f0))
    st = 12 * np.log2(f0 / med)
    return med, float(st.std())


def duration(data_or_path):
    x, sr = decode(data_or_path)
    return len(x) / sr


# ---------------------------------------------------------------- services

def fish_tts(text, voice_id, key, model="s2.1-pro-free", fmt="wav", tries=3):
    body = json.dumps({"text": text, "reference_id": voice_id, "format": fmt, "normalize": True}).encode()
    for i in range(tries):
        try:
            req = urllib.request.Request(FISH_TTS, data=body, headers={
                "Authorization": "Bearer " + key, "Content-Type": "application/json", "model": model})
            return urllib.request.urlopen(req, timeout=180).read()
        except Exception as e:
            if i == tries - 1:
                raise
            time.sleep(3 * (i + 1))


def fish_get(path, key):
    req = urllib.request.Request(FISH_API + path, headers={"Authorization": "Bearer " + key})
    return json.load(urllib.request.urlopen(req, timeout=60))


def fish_search(key, title=None, tags=(), page_size=20, sort_by="score"):
    q = [f"page_size={page_size}", f"sort_by={sort_by}", "language=en"]
    if title:
        q.append("title=" + urllib.parse.quote(title))
    for t in tags:
        q.append("tag=" + urllib.parse.quote(t))
    return fish_get("/model?" + "&".join(q), key).get("items", [])


def gemini_json(prompt, clips, key):
    """clips: [(label, wav bytes)] -> parsed JSON from the first model that answers."""
    parts = [{"text": prompt}]
    for label, wav in clips:
        parts.append({"text": f"Clip {label}:"})
        parts.append({"inline_data": {"mime_type": "audio/wav", "data": base64.b64encode(wav).decode()}})
    payload = json.dumps({"contents": [{"role": "user", "parts": parts}],
                          "generationConfig": {"temperature": 0, "responseMimeType": "application/json",
                                               "thinkingConfig": {"thinkingLevel": "low"}}}).encode()
    last = None
    for model in GEMINI_MODELS:
        for attempt in range(2):
            try:
                req = urllib.request.Request(GEMINI.format(model), data=payload,
                                             headers={"x-goog-api-key": key, "Content-Type": "application/json"})
                js = json.load(urllib.request.urlopen(req, timeout=300))
                ps = js["candidates"][0]["content"]["parts"]
                text = "".join(p.get("text", "") for p in ps if not p.get("thought")).strip()
                text = text.removeprefix("```json").removeprefix("```").removesuffix("```").strip()
                return json.loads(text)
            except Exception as e:
                last = e
                time.sleep(2)
    raise RuntimeError(f"gemini failed: {last}")


# ---------------------------------------------------------------- script access

SCRIPTS = ["tour_script.json", "tour2_script.json", "tour3_script.json"]
FOLDER = {"NAVIGATION": "navigation", "SCIENCE": "science", "ENGINEERING": "engineering"}


def role_lines(role):
    out = []
    for f in SCRIPTS:
        d = json.load(open(os.path.join(ASSETS, f)))
        for c in d["cues"] + d.get("fillers", []):
            if c.get("role") == role and c.get("clip") and c.get("text"):
                out.append((f, c["clip"], c["text"]))
    return out


def clip_path(role, clip):
    return os.path.join(ASSETS, "voice", FOLDER[role], clip + ".ogg")


# ---------------------------------------------------------------- commands

RATE_PROMPT = """You are a voice director checking recorded lines for a character in an audio drama:
{who}
Each clip is one line; its script, with the bracketed delivery cues the actor was given, is listed below.
Rate every clip from its SOUND:
  mechanical: robotic or synthetic timbre, monotone or flat pitch, evenly clipped words, every sentence ending the same way (1 = natural, 5 = very mechanical);
  expressive: how fully the delivery follows the bracketed cues and the meaning: tension, urgency, dry humour, awe, laughter (1 = reads it flat, 5 = fully acted);
  flat_spots: a few words quoting any stretch that goes monotone, or "" if none.
Measured pitch movement (lively speech moves about 3-5 semitones; under 2 is flat): {measured}
Scripts:
{scripts}
Return JSON: {{"ratings":[{{"clip":"A","mechanical":n,"expressive":n,"flat_spots":""}}]}}"""


def rate_batch(items, who, gkey):
    """items: [(label, wav, text, spread)] -> {label: rating}"""
    measured = "; ".join(f"{l}: {s:.1f} semitones" if s is not None else f"{l}: unmeasured" for l, _, _, s in items)
    scripts = "\n".join(f"{l}: {t}" for l, _, t, _ in items)
    js = gemini_json(RATE_PROMPT.format(who=who, measured=measured, scripts=scripts), [(l, w) for l, w, _, _ in items], gkey)
    return {r.get("clip"): r for r in js.get("ratings", [])}


def cmd_assess(a):
    gkey = read_key(a.gemini_key_file)
    lines = role_lines(a.role)
    if a.only:
        lines = [l for l in lines if a.only in l[1]]
    rows = []
    items = []
    for f, clip, text in lines:
        p = clip_path(a.role, clip)
        x, sr = decode(p)
        ps = pitch_spread(x, sr)
        items.append((clip, to_wav(p), text, ps[1] if ps else None, len(x) / sr))
    labels = [chr(65 + i) for i in range(26)]
    for i in range(0, len(items), a.batch):
        chunk = items[i:i + a.batch]
        lab = [(labels[j], w, t, s) for j, (_, w, t, s, _) in enumerate(chunk)]
        try:
            r = rate_batch(lab, a.who, gkey)
        except Exception as e:
            print("rate failed:", e, file=sys.stderr); r = {}
        for j, (clip, _, text, s, dur) in enumerate(chunk):
            rr = r.get(labels[j], {})
            rows.append({"clip": clip, "spread": s, "dur": round(dur, 1), "mechanical": rr.get("mechanical"),
                         "expressive": rr.get("expressive"), "flat_spots": rr.get("flat_spots", ""), "text": text})
        print(f"rated {min(i + a.batch, len(items))}/{len(items)}", file=sys.stderr)
    json.dump(rows, open(a.out, "w"), indent=1)
    sp = [r["spread"] for r in rows if r["spread"] is not None]
    me = [r["mechanical"] for r in rows if r["mechanical"]]
    ex = [r["expressive"] for r in rows if r["expressive"]]
    flat = [r for r in rows if (r["spread"] is not None and r["spread"] < 2.0) or (r["mechanical"] or 0) >= 3 or (r["expressive"] or 5) <= 2]
    print(f"{a.role}: {len(rows)} clips  pitch {np.mean(sp):.2f} st (min {min(sp):.2f})  mechanical {np.mean(me):.2f}  expressive {np.mean(ex):.2f}")
    print(f"flat or mechanical: {len(flat)}")
    for r in sorted(flat, key=lambda r: (r["spread"] or 0)):
        print(f"  {r['clip']:34s} {r['spread'] or 0:4.1f}st mech {r['mechanical']} expr {r['expressive']}  {r['flat_spots'][:60]}")


def cmd_audition(a):
    fkey = read_key(a.fish_key_from); gkey = read_key(a.gemini_key_file)
    voices = [v.strip() for v in a.voices.split(",") if v.strip()]
    lines = [l.strip() for l in open(a.lines) if l.strip()]
    script = " ".join(lines)
    takes = []
    for v in voices:
        for t in range(a.takes):
            try:
                wav = to_wav(fish_tts(script, v, fkey))
                takes.append((v, wav))
            except Exception as e:
                print(f"render failed {v}: {e}", file=sys.stderr)
    random.Random(7).shuffle(takes)
    labels = [chr(65 + i) for i in range(len(takes))]
    items = []
    for l, (v, wav) in zip(labels, takes):
        x, sr = decode(wav); ps = pitch_spread(x, sr)
        items.append((l, wav, script, ps[1] if ps else None))
    refs = []
    for rv in (a.refs.split(",") if a.refs else []):
        path = clip_path(rv.split(":")[0], rv.split(":")[1])
        refs.append(to_wav(path))
    prompt = f"""You are auditioning voices for this character in an audio drama:
{a.who}
Every clip performs the same script (with bracketed delivery cues); some clips may be the same voice.
{"The first " + str(len(refs)) + " reference clips (R1..) are OTHER characters in the same crew; the new voice must sound clearly different from them." if refs else ""}
Rate every audition clip from its sound:
  fit: gender, apparent age, timbre and manner against the character (1-5);
  mechanical: robotic or synthetic timbre, monotone or flat pitch, evenly clipped words, same sentence endings (1 = natural, 5 = very mechanical);
  acting: does the delivery really change with each cue (tense, urgent, dry, awe, chuckle) (1-5);
  distinct: how clearly different from the reference crew voices (1-5, 5 if no references).
Measured pitch movement (lively speech moves about 3-5 semitones; under 2 is flat): {"; ".join(f"{l}: {s:.1f}" if s else f"{l}: n/a" for l, _, _, s in items)}.
Script: {script}
Return JSON: {{"ratings":[{{"clip":"A","fit":n,"mechanical":n,"acting":n,"distinct":n,"note":""}}]}}"""
    clips = [(f"R{i + 1}", w) for i, w in enumerate(refs)] + [(l, w) for l, w, _, _ in items]
    js = gemini_json(prompt, clips, gkey)
    rat = {r.get("clip"): r for r in js.get("ratings", [])}
    per = {}
    for (l, wav, _, s), (v, _) in zip(items, takes):
        per.setdefault(v, []).append((rat.get(l, {}), s))
    res = []
    for v, rs in per.items():
        def avg(k):
            xs = [r.get(k) for r, _ in rs if isinstance(r.get(k), (int, float))]
            return float(np.mean(xs)) if xs else 3.0
        sp = [s for _, s in rs if s]
        pitch = float(np.mean(sp)) if sp else 0.0
        score = avg("fit") + 0.6 * avg("acting") - 0.8 * avg("mechanical") + 0.3 * min(pitch, 5) + 0.5 * avg("distinct")
        eligible = avg("fit") >= 3 and avg("mechanical") < 3 and pitch >= 2 and avg("distinct") >= 3
        res.append((score, eligible, v, avg("fit"), avg("mechanical"), avg("acting"), avg("distinct"), pitch,
                    "; ".join(r.get("note", "") for r, _ in rs)[:120]))
    res.sort(reverse=True)
    names = {}
    for v in per:
        try:
            names[v] = fish_get("/model/" + v, fkey).get("title", "")
        except Exception:
            names[v] = ""
    out = []
    for score, ok, v, fit, mech, act, dist, pitch, note in res:
        print(f"{'OK ' if ok else '-- '}{score:5.2f}  {names[v][:28]:28s} {v}  fit {fit:.1f} mech {mech:.1f} act {act:.1f} distinct {dist:.1f} pitch {pitch:.1f}st  {note}")
        out.append(dict(voice=v, title=names[v], score=score, eligible=ok, fit=fit, mechanical=mech, acting=act, distinct=dist, pitch=pitch, note=note))
    if a.out:
        json.dump(out, open(a.out, "w"), indent=1)
    if a.save_dir:
        os.makedirs(a.save_dir, exist_ok=True)
        for i, (v, wav) in enumerate(takes):
            open(os.path.join(a.save_dir, f"{names.get(v, '')[:20].replace(' ', '_')}_{v[:8]}_{i}.wav"), "wb").write(wav)


def cmd_regen(a):
    """Re-render every line of a role in [voice]: takes per line, rated blind, the best kept."""
    fkey = read_key(a.fish_key_from); gkey = read_key(a.gemini_key_file)
    lines = role_lines(a.role)
    if a.only:
        keep = set(a.only.split(","))
        lines = [l for l in lines if l[1] in keep or any(k in l[1] for k in keep)]
    os.makedirs(a.takes_dir, exist_ok=True)
    report = []

    def render(text):
        raw = fish_tts(text, a.voice, fkey)
        wav = to_wav(raw)
        x, sr = decode(wav); ps = pitch_spread(x, sr)
        return raw, wav, (ps[1] if ps else None)

    def judge(pending):
        """pending: [(clip, text, [takes])] -> {(clip, i): rating}"""
        items, keymap = [], []
        for clip, text, takes in pending:
            for i, (_, wav, s) in enumerate(takes):
                items.append((wav, text, s)); keymap.append((clip, i))
        order = list(range(len(items))); random.Random(len(items)).shuffle(order)
        labels = [chr(65 + j) for j in range(len(order))]
        lab = [(labels[j], items[o][0], items[o][1], items[o][2]) for j, o in enumerate(order)]
        r = rate_batch(lab, a.who, gkey)
        return {keymap[o]: r.get(labels[j], {}) for j, o in enumerate(order)}

    def score(rt, s):
        mech = rt.get("mechanical") or 3; expr = rt.get("expressive") or 3
        return expr - mech + 0.3 * min(s or 0, 5) - (1.0 if rt.get("flat_spots") else 0)

    def ok(rt):
        return (rt.get("mechanical") or 3) < 3 and (rt.get("expressive") or 3) >= 3 and not rt.get("flat_spots")

    for i in range(0, len(lines), a.batch):
        chunk = lines[i:i + a.batch]
        pending = []
        for f, clip, text in chunk:
            takes = []
            for t in range(a.takes):
                try:
                    takes.append(render(text))
                except Exception as e:
                    print(f"  ! {clip} take {t}: {e}", file=sys.stderr)
            pending.append((clip, text, takes))
        rat = judge([p for p in pending if p[2]])
        for rnd in range(2):
            redo = []
            for clip, text, takes in pending:
                best = max(range(len(takes)), key=lambda k: score(rat.get((clip, k), {}), takes[k][2]), default=None)
                if best is not None and not ok(rat.get((clip, best), {})) and rnd == 0 and len(takes) < a.takes * 2:
                    redo.append(clip)
            if not redo:
                break
            print(f"  retake {', '.join(redo)}", file=sys.stderr)
            more = []
            for clip, text, takes in pending:
                if clip in redo:
                    new = []
                    for t in range(a.takes):
                        try:
                            new.append(render(text))
                        except Exception as e:
                            print(f"  ! {clip} retake: {e}", file=sys.stderr)
                    base = len(takes); takes.extend(new)
                    more.append((clip, text, new, base))
            r2 = judge([(c, t, n) for c, t, n, _ in more])
            for c, t, n, base in more:
                for k in range(len(n)):
                    rat[(c, base + k)] = r2.get((c, k), {})
        for clip, text, takes in pending:
            if not takes:
                report.append({"clip": clip, "error": "no takes"}); continue
            best = max(range(len(takes)), key=lambda k: score(rat.get((clip, k), {}), takes[k][2]))
            raw, wav, s = takes[best]; rt = rat.get((clip, best), {})
            for k, (_, w, _) in enumerate(takes):
                open(os.path.join(a.takes_dir, f"{clip}_{k}{'_KEPT' if k == best else ''}.wav"), "wb").write(w)
            out = clip_path(a.role, clip)
            tmp = out + ".src.wav"
            open(tmp, "wb").write(raw)
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", tmp, "-c:a", "libopus", "-b:a", "64k", "-ac", "1", out], check=True)
            os.unlink(tmp)
            report.append({"clip": clip, "takes": len(takes), "kept": best, "spread": s, "mechanical": rt.get("mechanical"),
                           "expressive": rt.get("expressive"), "flat_spots": rt.get("flat_spots", ""), "ok": ok(rt)})
            print(f"  {'ok ' if ok(rt) else '?? '}{clip:34s} take {best + 1}/{len(takes)}  mech {rt.get('mechanical')} expr {rt.get('expressive')} {s or 0:.1f}st  {rt.get('flat_spots', '')[:50]}")
        json.dump(report, open(a.out, "w"), indent=1)
    bad = [r for r in report if not r.get("ok")]
    print(f"done: {len(report)} lines, {len(report) - len(bad)} clean, {len(bad)} still flagged")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("assess"); s.add_argument("--role", default="ENGINEERING"); s.add_argument("--only", default="")
    s.add_argument("--who", required=True); s.add_argument("--batch", type=int, default=6); s.add_argument("--out", required=True)
    s.add_argument("--gemini-key-file", required=True)
    s = sub.add_parser("audition"); s.add_argument("--voices", required=True); s.add_argument("--lines", required=True)
    s.add_argument("--who", required=True); s.add_argument("--takes", type=int, default=2); s.add_argument("--refs", default="")
    s.add_argument("--out", default=""); s.add_argument("--save-dir", default="")
    s.add_argument("--fish-key-from", required=True); s.add_argument("--gemini-key-file", required=True)
    s = sub.add_parser("regen"); s.add_argument("--role", default="ENGINEERING"); s.add_argument("--voice", required=True)
    s.add_argument("--who", required=True); s.add_argument("--takes", type=int, default=2); s.add_argument("--batch", type=int, default=3)
    s.add_argument("--only", default=""); s.add_argument("--takes-dir", required=True); s.add_argument("--out", required=True)
    s.add_argument("--fish-key-from", required=True); s.add_argument("--gemini-key-file", required=True)
    a = ap.parse_args()
    {"assess": cmd_assess, "audition": cmd_audition, "regen": cmd_regen}[a.cmd](a)


if __name__ == "__main__":
    import urllib.parse
    main()
