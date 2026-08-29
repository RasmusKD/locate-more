#!/bin/sh
# The full release gate in one command: the golden battery on both
# supported versions. Flips gradle.properties to the 26.2 pair for the
# second run and restores it afterwards, whatever happens. Pass --bless
# to rewrite both goldens instead of diffing.
set -e
cd "$(dirname "$0")/.."

MC2=26.2
FABRIC2=0.158.0+26.2

test/run.sh "$@"

cp gradle.properties gradle.properties.runboth
trap 'mv gradle.properties.runboth gradle.properties' EXIT INT TERM
sed -i "s/^minecraft_version=.*/minecraft_version=$MC2/; s/^fabric_version=.*/fabric_version=$FABRIC2/" gradle.properties
test/run.sh "$@"
