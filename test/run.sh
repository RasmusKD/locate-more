#!/bin/sh
# Golden-file differential run: boots the dev server on the committed seed,
# feeds the battery, normalizes coordinates out of the log and diffs against
# the golden file for this MC version. Empty diff = the three replicated
# vanilla behaviors still hold. See PORTING.md.
#
# Usage: test/run.sh [--bless]   (--bless rewrites the golden file)
set -e
cd "$(dirname "$0")/.."

MC_VERSION=$(grep '^minecraft_version=' gradle.properties | cut -d= -f2)
GOLDEN="test/golden/$MC_VERSION.txt"

mkdir -p run/mods test/golden
cp test/server.properties run/server.properties
printf 'eula=true\n' > run/eula.txt
rm -rf run/world
LAB_JAR=$(ls ../locatemore-lab/build/libs/locatemore-lab-*.jar 2>/dev/null | tail -1)
rm -f run/mods/locatemore-lab-*.jar
if [ -n "$LAB_JAR" ]; then cp "$LAB_JAR" run/mods/; else echo "ADVARSEL: locatemore-lab ikke bygget - sync-differentialen springes over"; fi

{ sleep 75; while IFS= read -r cmd; do echo "$cmd"; sleep 5; done < test/commands.txt; sleep 45; echo stop; } | ./gradlew runServer -q > test/last-run.log 2>&1 || true

# Kun koordinat- og verify-linjer; tider og taellere er stoej.
grep -E '\[Server thread/INFO\]' test/last-run.log \
  | sed -E 's/^\[[0-9:]+\] \[Server thread\/INFO\] \(Minecraft\) //' \
  | grep -E '^([0-9]+\. \[|Shadow verify|The nearest)' \
  | sed -E 's/([0-9]+ agree)[^)]*/\1/' | sed -E 's/\([0-9]+ ms, skipKnown\)/(skipKnown)/' > test/last-run.txt

if [ "$1" = "--bless" ]; then
  cp test/last-run.txt "$GOLDEN"
  echo "GOLDEN OPDATERET: $GOLDEN ($(wc -l < "$GOLDEN") linjer) - skriv HVORFOR i commit-beskeden"
  exit 0
fi

if [ ! -f "$GOLDEN" ]; then
  echo "INGEN GOLDEN for $MC_VERSION - koer 'test/run.sh --bless' for at skabe den"
  exit 1
fi

if diff -u "$GOLDEN" test/last-run.txt; then
  echo "PORT-CHECK BESTAAET: $(wc -l < "$GOLDEN") linjer identiske"
else
  echo "PORT-CHECK FEJLET - se diff ovenfor. Tjek to koordinater mod en umodificeret"
  echo "vanilla-server paa samme seed, foer du overvejer --bless (se PORTING.md trin 4)."
  exit 1
fi
