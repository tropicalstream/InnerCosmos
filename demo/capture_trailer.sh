#!/bin/zsh
# Records the InnerCosmos trailer footage from the RayNeo X3 Pro with scrcpy (video + device audio),
# driving the app through its adb control broadcasts on a fixed timeline. Prints the wall-clock
# offset of every beat so the cut list can be built from the raw file.
set -u
S=A06B4A96A733283
OUT=/private/tmp/claude-501/-Users-me-Downloads/b3a19895-caad-4d70-9120-cc627029e290/scratchpad
RAW=$OUT/raw_trailer.mkv
LOG=$OUT/capture_marks.txt
ctl(){ adb -s $S shell am broadcast -a com.rayneo.innercosmos.CONTROL "$@" > /dev/null; }
T0=0
mark(){ local now=$(( $(date +%s) - T0 )); echo "$now $1" | tee -a $LOG; }

rm -f $RAW $LOG
adb -s $S shell am force-stop com.rayneo.innercosmos
adb -s $S shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1; adb -s $S shell cmd media_session volume --stream 3 --set 15 >/dev/null 2>&1
scrcpy -s $S --record=$RAW --no-playback --max-fps=30 --video-bit-rate=10M --audio-bit-rate=160K > $OUT/scrcpy_trailer.log 2>&1 &
PID=$!
sleep 3
T0=$(date +%s)
adb -s $S shell am start -n com.rayneo.innercosmos/.MainActivity >/dev/null
mark "launch (splash)"
sleep 9
ctl --ez board true;            mark "menu"
sleep 5
ctl --ei segment 0;             mark "seg0 threshold intro (script t=0)"
sleep 150                       # welcome, the face, the drive, the first drop (t=128), the nostril cave
ctl --ei segment 3;             mark "seg3 bloodstream jump"
sleep 16; ctl --ei view 1;      mark "seg3 view chase"
sleep 34
ctl --ei segment 4;             mark "seg4 heart jump"
sleep 16; ctl --ei view 1;      mark "seg4 view chase"
sleep 24; ctl --ei view 0;      mark "seg4 view bridge"
sleep 22
ctl --ei segment 5;             mark "seg5 sentinel jump"
sleep 16; ctl --ei view 1;      mark "seg5 view chase"
sleep 55
ctl --ei segment 6;             mark "seg6 neuron jump"
sleep 16; ctl --ei view 0;      mark "seg6 view bridge"
sleep 22; ctl --ei view 1;      mark "seg6 view chase"
sleep 22
ctl --ei segment 7;             mark "seg7 membrane jump"
sleep 16; ctl --ei view 1;      mark "seg7 view chase (arms)"
sleep 34
ctl --ei segment 9;             mark "seg9 nucleus jump"
sleep 16; ctl --ei view 1;      mark "seg9 view chase (helix)"
sleep 20; ctl --ei view 3;      mark "seg9 view deck"
sleep 20
ctl --ei segment 11;            mark "seg11 atom jump"
sleep 18; ctl --ei view 3;      mark "seg11 view deck"
sleep 22; ctl --ei view 0;      mark "seg11 view bridge"
sleep 20
ctl --ei segment 12;            mark "seg12 look back jump"
sleep 18; ctl --ei view 1;      mark "seg12 view chase (expansion)"
sleep 40; ctl --ei view 3;      mark "seg12 view deck (finale)"
sleep 70
mark "end"
kill -INT $PID
sleep 4
ls -la $RAW
ffprobe -v error -show_entries format=duration -show_entries stream=codec_type,codec_name -of compact $RAW
