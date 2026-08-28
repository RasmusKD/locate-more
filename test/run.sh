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
# Usage: test/run.sh [--bless]   (--bless rewrites the golden file)
set -e
cd "$(dirname "$0")/.."

MC_VERSION=$(grep '^minecraft_version=' gradle.properties | cut -d= -f2)
GOLDEN="test/golden/$MC_VERSION.txt"
START_TS=$(date +%s)

mkdir -p run/mods test/golden
cp test/server.properties run/server.properties
printf 'eula=true\n' > run/eula.txt
rm -rf run/world
cp test/commands.txt run/battery.txt

LAB_JAR=$(ls ../locatemore-lab/build/libs/locatemore-lab-*.jar 2>/dev/null | tail -1)
rm -f run/mods/locatemore-lab-*.jar
if [ -z "$LAB_JAR" ]; then
  echo "FEJL: locatemore-lab er ikke bygget - batteriet kan ikke koere (goldens indeholder lab-linjer og driveren bor i lab-mod'en)"
  exit 1
fi
cp "$LAB_JAR" run/mods/

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
    [ "$i" -gt 600 ] && break
  done
  echo "stop"
} | ./gradlew runServer -q > test/last-run.log 2>&1 || true

if ! grep -q 'Done (' test/last-run.log; then
  echo "FEJL: serveren bootede aldrig - se test/last-run.log"
  exit 1
fi
if ! grep -q 'BATTERY DONE' test/last-run.log; then
  echo "FEJL: batteriet blev aldrig faerdigt - se test/last-run.log"
  exit 1
fi

# Kun koordinat- og verify-linjer; tider og taellere er stoej.
grep -E '\[Server thread/INFO\]' test/last-run.log \
  | sed -E 's/^\[[0-9:]+\] \[Server thread\/INFO\] \(Minecraft\) //' \
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
