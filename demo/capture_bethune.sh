#!/bin/zsh
# Records Chapter III (Bethune) footage from the RayNeo X3 Pro with scrcpy, driving the app
# through its adb control broadcasts. Prints the wall-clock offset of every beat.
set -u
S=A06B4A96A733283
OUT=/private/tmp/claude-501/-Users-me-Downloads/b3a19895-caad-4d70-9120-cc627029e290/scratchpad
RAW=$OUT/raw_bethune.mkv
LOG=$OUT/bethune_marks.txt
ctl(){ adb -s $S shell am broadcast -a com.rayneo.innercosmos.CONTROL "$@" > /dev/null; }
T0=0
mark(){ local now=$(( $(date +%s) - T0 )); echo "$now $1" | tee -a $LOG; }

rm -f $RAW $LOG
adb -s $S shell input keyevent 224
sleep 2
adb -s $S shell am force-stop com.rayneo.innercosmos
adb -s $S shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1
adb -s $S shell cmd media_session volume --stream 3 --set 15 >/dev/null 2>&1
scrcpy -s $S --record=$RAW --no-playback --max-fps=30 --video-bit-rate=10M --audio-bit-rate=160K > $OUT/scrcpy_bethune.log 2>&1 &
PID=$!
sleep 4
T0=$(date +%s)
adb -s $S shell am start -n com.rayneo.innercosmos/.MainActivity >/dev/null
mark "splash"
sleep 10
ctl --ez board true;          mark "tour menu"
sleep 7
ctl --ei tour 3;              mark "depth menu"
sleep 6

ctl --ei segment 0;           mark "cavity"
sleep 22; ctl --ei view 1;    mark "cavity chase"
sleep 34; ctl --ei view 0;    mark "cavity bridge"
sleep 30
ctl --ei segment 1;           mark "vein jump"
sleep 18; ctl --ei view 1;    mark "vein chase"
sleep 30
ctl --ei segment 2;           mark "bottle jump"
sleep 16; ctl --ei view 1;    mark "bottle chase"
sleep 28
ctl --ei segment 3;           mark "front jump"
sleep 18; ctl --ei view 1;    mark "front chase (yanan plate)"
sleep 34; ctl --ei view 0;    mark "front bridge"
sleep 26
ctl --ei segment 4;           mark "transfusion jump"
sleep 16; ctl --ei view 1;    mark "transfusion chase"
sleep 28
ctl --ei segment 5;           mark "table jump"
sleep 18; ctl --ei view 1;    mark "table chase"
sleep 32
ctl --ei segment 6;           mark "students jump"
sleep 18; ctl --ei view 1;    mark "students chase"
sleep 32
ctl --ei segment 7;           mark "cut jump"
sleep 16; ctl --ei view 0;    mark "cut bridge"
sleep 26
ctl --ei segment 8;           mark "fever jump"
sleep 18; ctl --ei view 1;    mark "fever chase"
sleep 34
ctl --ei segment 9;           mark "memory jump"
sleep 20; ctl --ei view 3;    mark "memory deck (essay, bai qiuen)"
sleep 80;                     mark "coda begins (what became of it)"
sleep 30; ctl --ei view 1;    mark "coda chase (barefoot doctors)"
sleep 34;                     mark "coda numbers (life expectancy)"
sleep 34; ctl --ei view 3;    mark "coda deck (no parade)"
sleep 34;                     mark "coda circle closes"
sleep 44
mark "end"
kill -INT $PID
sleep 5
ls -la $RAW
