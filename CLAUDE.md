# LocateMore

Fabric mod (`environment: *`, server logic) that adds a count to `/locate`, searches off the server thread and fixes MC-138887. Mod id `locatemore`, GitHub RasmusKD/locate-more, branch main (26.3 scaffolding on `mc-26.3`). Memory note: `locatemore-mod.md`; porting checklist: `PORTING.md`.

Read the `minecraft-mods` skill first (`C:\Users\Rasmus\.claude\skills\minecraft-mods\SKILL.md`). It holds the cross-mod rules: separate jars per Minecraft line, release flow, testing, product rules.

## Build

- Java 25: `JAVA_HOME=C:\Users\Rasmus\.jdks\jdk-25.0.3+9`, then `./gradlew build` (runs the unit tests, `verifyJar` and `verifyMixinClasses`). The jar is `build/libs/locatemore-X.Y.Z+26.1.jar`.
- `-PmcTarget=26.3` is scaffolded but does not compile: 26.3 rewrote the worldgen sampling the mod replicates. See `VERSIONS.md` before touching it.
- The release gate is the differential run `test/run.sh` (boots a dev server); see `PORTING.md`.

## Release

Publish by hand through the Modrinth API (recipe in the memory note). Do not publish unless Rasmus asks.

## Keep skills and docs current (same rule as Nordvec)

When you learn something new, update the doc the next agent reads in the same change: this file for build and release facts, `README.md` for user-facing behavior, `VERSIONS.md` for version splits, and the `minecraft-mods` skill for cross-mod lessons. Correct or delete outdated text instead of adding a contradicting note. Then run `python C:\Users\Rasmus\.claude\skills\minecraft-mods\scripts\check_citations.py`.

No em or en dashes in any text. Commit messages describe the code change.
