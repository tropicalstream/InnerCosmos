# InnerCosmos

**Two stereoscopic rides through the human body for the RayNeo X3 Pro AR glasses.**
Board the *M.S.V. Mote*, a scale‑drive submersible, and ride a railed tour in real 3D
across both lenses, with a talking three‑person crew:

- **I. The Descent** (33 min) — a nostril to the inside of a carbon atom, shrinking one
  power of ten at a time.
- **II. The Living Machine** (35 min) — the mouth to a dividing cell by way of the gut's
  microbiome and its phages, the liver, the kidney, muscle, bone marrow, V(D)J
  recombination, kinesin on its microtubule highway, the protein factory, a single ATP
  synthase and mitosis — climbing back *up* the ladder several times along the way.

The sister project of **SpaceX3Tour** (Earth to Pluto). Same hardware, same engine,
same crew idea, same Fish Audio voices; the cosmos is now the one inside you.

> ⚠️ **ALPHA — proof of concept.** Expect rough edges. The **crew voices are AI
> text‑to‑speech (Fish Audio S2‑pro) and will change** as the project evolves.

---

## 🧭 Tour I — The Descent (13 stops down a powers‑of‑ten ladder)

| # | Stop | The Mote is… | What you see and hear |
| --- | --- | --- | --- |
| 1 | **The Threshold** | 12 m → 12 mm | the scale bay, a human face, the nostril as a cave mouth |
| 2 | **The Airway** | 1.2 mm | the epiglottis, C‑shaped cartilage rings, the mucus escalator |
| 3 | **The Alveolus** | 120 µm | inside one air sac, gas exchange through a wall thinner than a micron |
| 4 | **The Bloodstream** | 12 µm | red cells single‑file in a capillary, then the pulmonary vein |
| 5 | **The Heart** | 12 µm | the mitral valve slamming, the ventricle as a cathedral of muscle |
| 6 | **The Sentinel** | 12 µm | a neutrophil notices the ship — the chase |
| 7 | **The Neuron** | 1.2 µm → 120 nm | across the blood‑brain barrier, an action potential overtakes the ship, the synapse |
| 8 | **The Membrane** | 120 nm | the lipid bilayer, channel proteins, endocytosis |
| 9 | **The Mitochondrion** | 120 nm | cristae and ATP synthase turbines |
| 10 | **The Nucleus** | 12 nm | the nuclear pore, chromatin, the double helix, a polymerase at work |
| 11 | **The Ribosome** | 12 nm | messenger RNA read three letters at a time; a protein folds |
| 12 | **The Atom** | 12 pm | a three‑stage drop into a carbon atom: the electron cloud, the nucleus, the emptiness |
| 13 | **The Look Back** | 12 pm → 12 m | twelve powers of ten in ninety seconds, and the finale |

## ⚙️ Tour II — The Living Machine (13 stops, up and down the ladder)

| # | Stop | The Mote is… | What you see and hear |
| --- | --- | --- | --- |
| 1 | **The Mouth** | 12 m → 12 mm | teeth like cliffs, the tongue, the epiglottis, swallowed by peristalsis |
| 2 | **The Gut** | 1.2 mm → 12 µm | villi waving in the flow, then the colon's microbiome |
| 3 | **The Phage** | 120 nm | bacteriophages landing on a bacterium like lunar modules; the host bursts (lysis) |
| 4 | **The Liver** | 12 µm | hepatocyte plates along a sinusoid, bile canaliculi, a Kupffer cell |
| 5 | **The Kidney** | 12 µm | the glomerulus: a capillary knot in Bowman's capsule, filtrate dripping into the tubule |
| 6 | **The Muscle** | 1.2 µm | sarcomeres — myosin and actin bands sliding together on every twitch |
| 7 | **The Marrow** | 12 µm | trabecular bone, a megakaryocyte shedding platelets, a stem cell dividing |
| 8 | **The Shuffle** | 12 nm | V(D)J recombination: RAG picks one V, one D, one J and stitches a new antibody gene |
| 9 | **The Highway** | 120 nm | **kinesin** walking a microtubule hand over hand, hauling a vesicle many times its size; dynein passing the other way |
| 10 | **The Factory** | 12 nm | insulin end to end: ribosome on the rough ER, the chain threads in, Golgi, a vesicle spills out |
| 11 | **The Motor** | 1.2 nm | one ATP synthase, big as a building: the c‑ring turning, protons pouring through, ATP flung out |
| 12 | **The Division** | 1.2 µm | mitosis: chromosomes line up, split, ride the spindle, the cell pinches in two |
| 13 | **The Look Back** | 1.2 µm → 12 m | out through the mouth to the whole person: a community of 37 trillion cells |

Both tours share the same craft, controls, HUD, body map and voices; each stop of each
tour is data (`TourMap.kt`: rail position, passage radius and colour, scene, ambience
family, body‑map marker, ladder label) and the renderer rebuilds the passage when the
tour changes. Tour II adds a **grow** cue (the mirror image of a shrink: the world
contracts about the Mote and the hull swells under teal streaks) and a **lysis** cue
(the phage stop's infected host bursts in sync with the sound), plus gut, muscle and
motor ambiences.

### The spine of the ride
The script is built on the **foundations of science** — evidence over authority,
honest measurement, the willingness to be wrong — and on **Stephen Jay Gould's
teaching that humankind is one biological family.** It surfaces four times, each
time as something you can see out the window:

- **the skin** (melanin: every human has about the same melanocytes; the difference
  is a few genes and a layer as thick as a sheet of paper),
- **the blood** (there is no race in a red cell; type, never ancestry, decides a transfusion),
- **the DNA** (any two people are ~99.9% identical, and most of the 0.1% varies *within*
  any population, not between them; we are one young African species that never split),
- **the finale**: we are all human beings.

---

## 🎮 Controls (right‑arm touchpad)

| Action | What it does |
| --- | --- |
| **Tap** (title card) | Board — opens the tour menu |
| **Swipe / Tap** (tour menu) | Choose The Descent or The Living Machine |
| **Swipe / Tap** (depth menu) | Choose a starting depth / engage the scale drive (last row: back to the tour menu) |
| **Tap** (tour) | **Switch camera view** — Bridge → External → Scale Drive Core → Observation Deck |
| **Double‑tap** (tour) | **Pause** and reopen the depth menu |
| **Swipe forward** (tour) | **Cycle the audio mix** — Full → Dialogue+Ambient → Ambient+SFX → Mute |
| **Swipe back** (tour) | **Toggle the telemetry HUD** |

Starting anywhere is not a teleport: the Mote visibly races the rail to the chosen
depth (5–20 s) before the script resumes.

**Head look‑around.** Turn your head and the view turns with it (the glasses' IMU, gyro +
accelerometer). Only the look direction moves; the rail, the ship and the scripted camera
reframings are untouched, and a held offset drifts gently back to centre over about a
minute so gyro drift never strands you facing a wall. The pose you hold when you board is
"straight ahead"; `adb shell am broadcast -a com.rayneo.innercosmos.CONTROL --ez recenter true`
re‑centres, `--ez gaze false` disables it.

**Breathing.** In the nose, the airway and the alveolus the air is visible: dust and faint
streaks ride the breath deeper on the inhale and back toward the nose on the exhale, in step
with the breathing you hear, and the Mote surges gently on the same rhythm.

**Scale drops.** Every power‑of‑ten step is staged as a physical event: the world balloons
outward around the Mote (walls rush past, particles are flung outward, everything ahead
recedes) while the hull itself dwindles in the external view, then the camera settles back
in under radial streaks and a descending sweep.

### The four views
- **Bridge (Helm):** the porthole, looking ahead down the passage.
- **External / Chase:** a camera trailing the Mote, following it through every bend.
- **Scale Drive Core:** inside the hull, aft of the spinning rotor (the engineer's favourite place).
- **Observation Deck:** a calm, wide view beside the hull with a soft ambient pad.

### The Mote
An original industrial hovercraft (think working vessel, not sports car): a long faceted
hull, a raised cockpit pod, dorsal antenna masts, six glowing hover pads under the side
pontoons, an aft engine block with the scale‑drive ring, and **two articulated arm probes**
at the bow. The probes stay folded along the hull in transit and reach out to touch the
world at the alveolus wall, the neutrophil, the membrane, the double helix and the
ribosome, and whenever the script fires a spark, squelch or impact cue.

### Demo / adb control (for recording and testing)
```bash
adb shell am broadcast -a com.rayneo.innercosmos.CONTROL --ez board true    # leave the title card
adb shell am broadcast -a com.rayneo.innercosmos.CONTROL --ei tour 2        # load tour 1 or 2 (opens its depth menu)
adb shell am broadcast -a com.rayneo.innercosmos.CONTROL --ei segment 4     # jump to stop 5 (0-based index)
adb shell am broadcast -a com.rayneo.innercosmos.CONTROL --ei view 1        # camera 0..3
adb shell am broadcast -a com.rayneo.innercosmos.CONTROL --ez menu true     # pause + depth menu
adb shell am broadcast -a com.rayneo.innercosmos.CONTROL --ez hud false     # hide the telemetry
adb shell am start -n com.rayneo.innercosmos/.MainActivity --ez mono true   # single flat view (phones)
```
On an emulator or phone the app renders one flat view automatically; on the X3 Pro it renders
both eyes side‑by‑side.

### Body map (top right)
A small human silhouette with a glowing marker that travels between the organs of the
current tour (nose → trachea → lung → heart → carotid → brain on The Descent; mouth → gut →
liver → kidney → arm muscle → femur marrow → liver cell → pancreas → heart → gut on The Living
Machine), and gains concentric "zoom" rings once the ride is inside a cell. The label
beneath names the tissue (e.g. `MARROW · B CELL`).

### Heat and battery
The X3 Pro is a small, fanless device. The app renders both eyes at **30 fps** (not the
panel's full refresh) and listens to Android's thermal status: at *moderate* it drops to
20 fps with fewer particles and simpler walls, at *severe* to 15 fps. The synthesized
ambience runs at 22 kHz. If the glasses still get hot on a long unplugged session, start
from a later depth rather than running the full 33 minutes back to back.

### On‑screen telemetry (refreshed every 10 s)
Craft, view, the stop you departed and the one you're approaching, leg progress,
the Mote's current length and magnification, and the **powers‑of‑ten ladder** with
the current rung bracketed:

```
SCALE  12m  12mm  1.2mm [120µm] 12µm  1.2µm  120nm  12nm  1.2nm  120pm  12pm
```

Every crew line is also shown as a **closed caption** at the bottom of the view.

### The crew (voice announcements)
- **Helm** (Pilot) — calls every approach and every scale drop. Centre.
- **Doc** (Physiologist) — the wonder, the facts, the finale. Panned right.
- **Engineering** — owns the scale drive, sweats the immune system, chuckles. Panned left.

Helm and Engineering take priority; Doc waits her turn so nobody gets cut off.
Silences longer than ten seconds are filled with crew banter, and no line, scripted or
banter, is ever spoken twice in a ride.

---

## 📦 Install (no build required)
Sideload the APK onto the glasses:

```bash
adb install -r InnerCosmos-debug.apk
```

## 🛠 Build from source
Requirements: Android SDK (compileSdk 35), JDK 17.

```bash
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/InnerCosmos-debug.apk
adb install -r app/build/outputs/apk/debug/InnerCosmos-debug.apk
```

The RayNeo Mercury / IPC SDKs are bundled under `app/libs/`.

### Voices
Each ride — timing, dialog, view cuts, sound cues, the menu's start points — lives in
one file: `app/src/main/assets/tour_script.json` (The Descent) and `tour2_script.json`
(The Living Machine). They feed both the app and the voice generator, so what is said
and what is shown can never drift apart. Tour II's clips are prefixed `t2_`, and its
powers-of-ten HUD ladder is *derived* from its own shrink/grow cues, so the rung on
screen always matches the figure the crew has just spoken.

Check a script against what the app can actually play (sound-cue names, camera indices,
stop count, sorted cues, unique clip ids **and** unique dialog, rendered voice files):

```bash
python3 app/tools/validate_script.py app/src/main/assets/tour2_script.json
```

1. Copy the Fish Audio config from the sister project (or fill in the example):
   ```bash
   cp ../SpaceX3Tour-BKP7-20/app/tools/fish_audio.config app/tools/fish_audio.config
   ```
2. Pre‑render every line with **Fish Audio s2.1‑pro** (renders only missing clips; `--force` to redo).
   The three voice ids are the same reference voices as SpaceX3Tour / x3constellation:
   ```bash
   python3 app/tools/generate_fish_audio.py                       # The Descent
   python3 app/tools/generate_fish_audio.py --script app/src/main/assets/tour2_script.json
   # or take a valid key from another project's config without copying it here:
   python3 app/tools/generate_fish_audio.py --key-from ../x3cycles/tools/fish.config --model s2.1-pro
   ```

Until clips exist the app falls back, in order, to a cached Fish clip, live Fish
synthesis (the key is baked into `BuildConfig` from the config file), then on‑device
TextToSpeech — so the tour runs before any voice has been generated. Note that the
X3 Pro ships **without** a speech engine, so on the glasses the crew is silent until
clips are bundled or a valid Fish key is baked in.

For development without a Fish key there is a stand‑in generator that renders the
same clips with the Mac's built‑in voices (three distinct voices, clearly not S2‑pro):
```bash
python3 app/tools/generate_placeholder_voices.py       # macOS only
python3 app/tools/generate_fish_audio.py --force        # later: replace them with Fish S2-pro
```

Optional sound effects go in `app/src/main/assets/sfx/` (see the README there); a
built‑in synth cue stands in for any that are missing.

---

## 🎬 Demo trailer
`demo/InnerCosmos_trailer.mp4` (and a smaller `_share` copy) is a four‑minute trailer cut
from the glasses' own output: `demo/capture_trailer.sh` records the app with scrcpy (video +
device audio) while driving it over the adb control channel, and `demo/assemble_trailer.py`
crops the left eye, interleaves the title cards (`demo/card_*.svg`) and mixes a synthesized
organ‑drone bed under the crew. Both scripts assume the X3 Pro's adb serial; edit the top of
each to reuse them.

## Notes & credits
- Renders with **native OpenGL ES 2.0** (no Unity, no textures): a single continuous
  passage that changes radius, colour and wall texture as the Mote shrinks, lit by
  the ship's own bow lamp, plus a procedural landmark at every stop. Two eye
  viewports side‑by‑side for the X3 Pro lenses; all 2D overlays are mirrored into
  both lenses by `BinocularSbsLayout`.
- The ambience is synthesised on the fly (breath in the airway, a resting heartbeat
  in the vessels, neural crackle in the cortex, a cytoplasm hum, a glassy shimmer in
  the atom) and follows the tour node.
- The Mote and its scale drive are fiction — the one piece of magic aboard. Every
  number the crew says is real, rounded honestly, and was adversarially fact‑checked
  during writing (see `SCRIPT.md`).
- **RayNeo** — X3 Pro hardware and AR SDKs. **Fish Audio** — text‑to‑speech voices.
- Written for high‑school through college audiences, in the spirit of Sagan, Tyson,
  Gould, Roddenberry and a theme‑park dark ride.

A personal, non‑commercial project.
