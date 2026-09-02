Runtime assets for InnerCosmos:

  tour_script.json   the whole ride: keyframes (time -> depth), timed cues
                     (dialog, view changes, sfx), silence fillers, and the
                     start-point segments shown in the menu. Single source of
                     truth for both the app and tools/generate_fish_audio.py.
  voice/             pre-rendered crew lines (see voice/README.txt)
  sfx/               optional sound effects (see sfx/README.txt)

Everything renders procedurally (OpenGL ES 2.0): no textures are required.
