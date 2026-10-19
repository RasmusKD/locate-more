#!/bin/sh
# Golden-file differential run: boots the dev server on the committed seed,
# hands the battery list to the lab mod, and diffs normalized coordinates
# against the golden file for this MC version. Empty diff = the replicated
# vanilla behaviors still hold. See PORTING.md.
#
# Signal-paced, not time-paced: the shell only polls the log for two
# markers ("Done (" = server ready, "BATTERY DONE" = list finished). The
# lab mod runs the command list itself and awaits engine idleness between
# commands, so output interleaving is impossible by construction and the
# run takes as long as the searches, not as long as the sleeps.
#
# The driver is the dev-only locatemorelab source set (src/lab), started by
# the labServer run configuration; it never enters the shipped jar.
#
# Usage: [MC_TARGET=26.3] test/run.sh [--bless]   (--bless rewrites the golden file)
# BATTERY=<file> RAW_ONLY=1 test/run.sh runs another command list and stops
# after the raw log (test/last-run.log), for test/oracle.py.
set -e
cd "$(dirname "$0")/.."

# MC_TARGET=26.3 runs the 26.3 variant (pins in gradle-26.3.properties).
if [ "$MC_TARGET" = "26.3" ]; then
  GRADLE_ARGS="-PmcTarget=26.3"
  MC_VERSION=$(grep '^minecraft_version=' gradle-26.3.properties | cut -d= -f2)
else
  GRADLE_ARGS=""
  MC_VERSION=$(grep '^minecraft_version=' gradle.properties | cut -d= -f2)
fi
GOLDEN="test/golden/$MC_VERSION.txt"
START_TS=$(date +%s)

mkdir -p run/mods test/golden
cp test/server.properties run/server.properties
# The oracle battery runs vanilla's own locate on the server thread; MAX_TICK_TIME=-1 keeps the watchdog from killing a slow one.
[ -n "$MAX_TICK_TIME" ] && echo "max-tick-time=$MAX_TICK_TIME" >> run/server.properties
printf 'eula=true\n' > run/eula.txt
rm -rf run/world
cp "${BATTERY:-test/commands.txt}" run/battery.txt
rm -f run/mods/locatemore-lab-*.jar

# The server is a background job on a shared PC: lowest CPU priority, as soon
# as its JVM exists (Windows only; a no-op elsewhere).
if command -v powershell.exe >/dev/null 2>&1; then
  (
    i=0
    while [ "$i" -lt 180 ]; do
      sleep 2; i=$((i+1))
      powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'KnotServer' } | ForEach-Object { (Get-Process -Id \$_.ProcessId).PriorityClass = 'Idle' }" >/dev/null 2>&1
      grep -q 'BATTERY DONE' test/last-run.log 2>/dev/null && break
    done
  ) &
fi

: > test/last-run.log
{
  i=0
  until grep -q 'Done (' test/last-run.log 2>/dev/null; do
    sleep 1; i=$((i+1))
    [ "$i" -gt 300 ] && break
  done
  echo "lmlab battery battery.txt"
  i=0
  until grep -q 'BATTERY DONE' test/last-run.log 2>/dev/null; do
    sleep 1; i=$((i+1))
    [ "$i" -gt "${BATTERY_TIMEOUT:-600}" ] && break
  done
  echo "stop"
} | ./gradlew runLabServer $GRADLE_ARGS -q > test/last-run.log 2>&1 || true

if ! grep -q 'Done (' test/last-run.log; then
  echo "FEJL: serveren bootede aldrig - se test/last-run.log"
  exit 1
fi
if ! grep -q 'BATTERY DONE' test/last-run.log; then
  echo "FEJL: batteriet blev aldrig faerdigt - se test/last-run.log"
  exit 1
fi

if [ -n "$RAW_ONLY" ]; then
  echo "RAA LOG: test/last-run.log"
  exit 0
fi

# Kun koordinat- og verify-linjer; tider og taellere er stoej.
grep -E '\[Server thread/INFO\]' test/last-run.log \
  | sed -E 's/^\[[0-9:]+\] \[Server thread\/INFO\] \(Minecraft\) //'   | sed -E 's/^System chat: //' \
  | grep -E '^([0-9]+\. \[|Shadow verify|The nearest)' \
  | sed -E 's/([0-9]+ agree)[^)]*/\1/' | sed -E 's/\([0-9]+ ms, skipKnown\)/(skipKnown)/' > test/last-run.txt

TOOK=$(( $(date +%s) - START_TS ))

if [ "$1" = "--bless" ]; then
  cp test/last-run.txt "$GOLDEN"
  echo "GOLDEN OPDATERET: $GOLDEN ($(wc -l < "$GOLDEN") linjer, ${TOOK}s) - skriv HVORFOR i commit-beskeden"
  exit 0
fi

if [ ! -f "$GOLDEN" ]; then
  echo "INGEN GOLDEN for $MC_VERSION - koer 'test/run.sh --bless' for at skabe den"
  exit 1
fi

if diff -u "$GOLDEN" test/last-run.txt; then
  echo "PORT-CHECK BESTAAET: $(wc -l < "$GOLDEN") linjer identiske (${TOOK}s)"
else
  echo "PORT-CHECK FEJLET - se diff ovenfor. Tjek to koordinater mod en umodificeret"
  echo "vanilla-server paa samme seed, foer du overvejer --bless (se PORTING.md trin 4)."
  exit 1
fi
