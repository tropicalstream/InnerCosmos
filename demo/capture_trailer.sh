#!/bin/zsh
# Records the InnerCosmos trailer footage from the RayNeo X3 Pro with scrcpy (video + device audio),
# driving BOTH tours through the app's adb control broadcasts on a fixed timeline. Prints the
# wall-clock offset of every beat so the cut list can be built from the raw file.
set -u
S=A06B4A96A733283
OUT=/private/tmp/claude-501/-Users-me-Downloads/b3a19895-caad-4d70-9120-cc627029e290/scratchpad
RAW=$OUT/raw_trailer2.mkv
LOG=$OUT/capture_marks2.txt
ctl(){ adb -s $S shell am broadcast -a com.rayneo.innercosmos.CONTROL "$@" > /dev/null; }
T0=0
mark(){ local now=$(( $(date +%s) - T0 )); echo "$now $1" | tee -a $LOG; }

rm -f $RAW $LOG
adb -s $S shell input keyevent 224            # the glasses doze between sessions
sleep 2
adb -s $S shell am force-stop com.rayneo.innercosmos
adb -s $S shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1
adb -s $S shell cmd media_session volume --stream 3 --set 15 >/dev/null 2>&1
scrcpy -s $S --record=$RAW --no-playback --max-fps=30 --video-bit-rate=10M --audio-bit-rate=160K > $OUT/scrcpy_trailer2.log 2>&1 &
PID=$!
sleep 4
T0=$(date +%s)
adb -s $S shell am start -n com.rayneo.innercosmos/.MainActivity >/dev/null
mark "splash"
sleep 10
ctl --ez board true;              mark "tour menu"
sleep 6

# ---------------------------------------------------------------- I. THE DESCENT
ctl --ei tour 1;                  mark "t1 depth menu"
sleep 5
ctl --ei segment 0;               mark "t1 threshold"
sleep 20; ctl --ei view 1;        mark "t1 threshold chase"
sleep 105;                        mark "t1 first drop window"     # the three-decade drop lands ~t=128
sleep 26
ctl --ei segment 3;               mark "t1 blood jump"
sleep 18; ctl --ei view 1;        mark "t1 blood chase"
sleep 26
ctl --ei segment 4;               mark "t1 heart jump"
sleep 18; ctl --ei view 1;        mark "t1 heart chase"
sleep 24; ctl --ei view 0;        mark "t1 heart bridge"
sleep 20
ctl --ei segment 5;               mark "t1 sentinel jump"
sleep 16; ctl --ei view 1;        mark "t1 sentinel chase"
sleep 34
ctl --ei segment 9;               mark "t1 nucleus jump"
sleep 18; ctl --ei view 1;        mark "t1 nucleus chase"
sleep 26; ctl --ei view 3;        mark "t1 nucleus deck"
sleep 22
ctl --ei segment 11;              mark "t1 atom jump"
sleep 18; ctl --ei view 3;        mark "t1 atom deck"
sleep 26
ctl --ei segment 12;              mark "t1 look back jump"
sleep 18; ctl --ei view 1;        mark "t1 expansion chase"
sleep 40; ctl --ei view 3;        mark "t1 finale deck"
sleep 40

# ---------------------------------------------------------- II. THE LIVING MACHINE
ctl --ei tour 2;                  mark "t2 depth menu"
sleep 6
ctl --ei segment 0;               mark "t2 mouth"
sleep 14; ctl --ei view 0;        mark "t2 mouth bridge"
sleep 22; ctl --ei view 1;        mark "t2 mouth chase"
sleep 26
ctl --ei segment 1;               mark "t2 gut jump"
sleep 16; ctl --ei view 1;        mark "t2 gut chase"
sleep 30
ctl --ei segment 2;               mark "t2 phage jump"
sleep 16; ctl --ei view 1;        mark "t2 phage chase"
sleep 24; ctl --ez lysis true;    mark "t2 lysis"
sleep 16
ctl --ei segment 4;               mark "t2 kidney jump"
sleep 18; ctl --ei view 0;        mark "t2 kidney bridge"
sleep 24
ctl --ei segment 5;               mark "t2 muscle jump"
sleep 16; ctl --ei view 1;        mark "t2 muscle chase"
sleep 26
ctl --ei segment 6;               mark "t2 marrow jump"
sleep 16; ctl --ei view 1;        mark "t2 marrow chase"
sleep 26
ctl --ei segment 7;               mark "t2 shuffle jump"
sleep 16; ctl --ei view 1;        mark "t2 shuffle chase"
sleep 28
ctl --ei segment 8;               mark "t2 highway jump"
sleep 16; ctl --ei view 1;        mark "t2 kinesin chase"
sleep 30; ctl --ei view 3;        mark "t2 kinesin deck"
sleep 24
ctl --ei segment 10;              mark "t2 motor jump"
sleep 18; ctl --ei view 1;        mark "t2 motor chase"
sleep 28; ctl --ei view 3;        mark "t2 motor deck"
sleep 22
ctl --ei segment 11;              mark "t2 division jump"
sleep 18; ctl --ei view 1;        mark "t2 division chase"
sleep 32
ctl --ei segment 12;              mark "t2 t2 look back"
sleep 18; ctl --ei view 3;        mark "t2 finale deck"
sleep 45
mark "end"
kill -INT $PID
sleep 5
ls -la $RAW
ffprobe -v error -show_entries format=duration -show_entries stream=codec_type,codec_name -of compact $RAW
