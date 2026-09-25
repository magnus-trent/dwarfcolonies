# Review log

What has been changed on this fork, and what is still open on each. Newest work last.

Base: `686b52b3e7` (upstream `ldtteam/minecolonies`, branch `version/1.21`).
Fork: `magnus-trent/dwarfcolonies`, pushed to `version/1.21`.

**Nothing below has been run.** This repo has no test source set — `src/` holds only `main` and `datagen`,
and `:test` reports `NO-SOURCE`. Every commit passes `./gradlew build`, which is compilation and datagen and
nothing else. Where an item says "verified", it means verified by reading the code it touches, not by
execution.

| # | Change | Commit | Build | Run in game |
|---|---|---|---|---|
| 1 | Several builders on one structure | `3077d34d2d` | pass | no |
| 2 | Multi-builder handoff notes | `371c9f3829` | n/a | n/a |
| 3 | Mine stops digging tunnels | `d7334fa7f1` | pass | no |
| 4 | Mine reproduces surveyed ore | `2f62d58278` | pass | no |

---

## 1. Several builders on one structure — `3077d34d2d`

A builder's hut takes two workers per level (2 to 10 across its five levels) and all of them can work the
same work order. Design detail and the reasoning behind it is in `HANDOFF_MULTIBUILDER.md`; this entry is
only the review status.

Files: `BuildWorkScheduler` (new), `WorkerCapacity` (new), `AbstractBuildingStructureBuilder`,
`AbstractEntityAIStructure`, `BuildingResourcesModule`, `BuildingModules`, `AbstractBuildingBuilderView`.

**Open for review — behavior:**
- Single builder must behave exactly as before. This is the regression that matters most, and the reason the
  final sweep is skipped when only one lane was ever handed out. Check this before anything else.
- Two builders on one work order: both placing, neither overlapping, build completes.
- Finished structure against the blueprint, looking for holes. Holes mean the sweep is not closing gaps.
- A build that stalls partway means the stage barrier never released. `getLaneCount()` and `getStage()` on
  the scheduler exist for exactly this.
- Save and reload mid-build. Lanes are deliberately not persisted, only the stage; the crew should
  re-acquire and carry on.
- Kill a builder mid-stage and confirm the sweep picks up its share.

**Open for review — decisions I made that are yours to overrule:**
- Two workers per hut level. Picked to match your 2/4/6/8/10 curve. It also makes auto-hire pull jobless
  citizens harder, and `calculateMaxCitizens` ties back to housing, so this is a population and food change
  as much as a staffing one.
- The material bucket is now sized to the whole crew rather than one worker, so trip count no longer
  multiplies with crew size. It is still a *single* bucket, so material throughput does not run in parallel.
  Whether it should become one bucket per worker is a gameplay call, still open.

**Known and deliberate:**
- `CLEAR_WATER` cannot be laned: `placer.clearWaterStep()` takes no predicate, so every worker runs it over
  the whole structure. Idempotent, so correct, but redundant work.
- A worker waiting at a stage boundary loops returning `getState()`. Correct, mildly wasteful.

**Fixed during the work, listed because no test caught them and they are easy to reintroduce:**
- The lane was acquired against the worker's stale local stage, before syncing from `building.getProgress()`.
- Each worker was seeded from the building's *shared* `progressPos`, which every worker overwrites every
  step, so two builders would have dragged each other's cursor around the structure.

---

## 3. Mine stops digging tunnels — `d7334fa7f1`

The shaft stays, the horizontal sprawl is gone. `MINER_MINING_NODE` is no longer registered or reachable and
the node mining, node search and node blueprint placement go with it, along with the half of the completion
actions that closed a finished node. 247 lines removed.

Depth is no longer capped by hut level: the old limit stepped through `MINING_LEVELS` once per level. The
limit is now the player's own maximum-depth setting kept inside the world's build height, and pace comes from
whether build-down materials keep arriving.

Files: `EntityAIStructureMiner`, `BuildingMiner`.

**Open for review — behavior:**
- A miner digs the shaft down and keeps going as long as cobble and ladders arrive.
- The miner never wanders off sideways.
- What the miner does on reaching the depth limit. It returns `IDLE`, which is correct in that there is
  nothing left to dig, but whether an idle miner standing in a hut is the behavior you want is a design
  question I did not decide.
- Existing saves with tunnels already dug: the tunnels stay in the world as holes, nothing cleans them up,
  and the miner should ignore them.

**Open for review — decisions I made that are yours to overrule:**
- `MinerLevel` and `MineNode` were left in place. Levels still record shaft landings and are read by the hut
  GUI, the walk-to proxy and old saves; removing them means touching 18 files including guard AI and
  pathing, with nothing to verify against. Only the tunnelling is gone. If you want the classes actually
  deleted, that is a separate pass.
- The hut GUI still shows the old level list, which will now only ever list shaft landings.

**Fixed during the work:**
- I called the node methods a closed island and deleted the range; `secureBlock` lived inside it and the
  *shaft* path calls it. Caught by the compiler, restored from git.
- `getWorkingPosition` positioned the miner relative to its active tunnel node. This would have failed
  silently rather than at build time: the node grid is no longer maintained, but `getActiveNode()`
  synthesises a random node from the current level when none is set, so it would have kept handing back
  tunnels that were never dug and sent the miner to stand in them.

---

## 4. Mine reproduces surveyed ore — `2f62d58278`

The mine surveys the ground it covers and pays out a share daily. Ore in the world is left alone and read
only as a measure of richness, so a mine never exhausts its ground. Radius is one chunk per hut level (level
one covers nine chunks, level five covers 121). Depth is counted in sixteen-block bands and the bands
accumulate, so descending only adds to output.

Each chunk band is counted once and remembered, four per colony tick, and an unloaded chunk is left for a
later tick rather than force-loaded. Once the whole area is counted the sweep stops re-walking it, keyed on
(radius, top band, reached band) so it reopens on upgrade or descent.

Files: `MineSurveyModule` (new), `BuildingMiner`, `BuildingModules`, `ModBuildingsInitializer`.

**Open for review — the number.** `DAILY_YIELD_FRACTION` is `0.02` and is very likely far too generous.
The arithmetic, so you can judge it rather than take my word: a one-chunk sixteen-block band is 4,096 blocks
and might hold 10–20 iron; across 121 chunks that is roughly 1,500–2,400 iron per band; at 2% that is 30–48
iron per day *per band*, and bands accumulate, so ten bands deep on a level five mine lands near 300–480
iron a day. I would expect the right value to be nearer `0.001`. It was left at `0.02` because you asked for
a hardcoded starting value, and changing it quietly would have hidden the estimate from you.

**Open for review — behavior:**
- Ore actually arrives in the hut, once per colony day.
- Output grows when the shaft descends into a new band, and when the hut is upgraded.
- Survey cost. Watch for a tick-time spike the first time a deep or high-level mine surveys new ground; the
  four-per-tick budget is a guess, not a measurement.
- Chunks outside player range: the survey should quietly wait for them, not skip them permanently and not
  force them loaded.
- Save and reload: surveyed cells and ore totals persist, and the mine does not re-survey or double-pay.
- Modded ores, via `CompatibilityManager.isOre`.

**Open for review — decisions I made that are yours to overrule:**
- Ore to item asks the block's loot table once and uses the result as the unit of production, so redstone
  and lapis pay a fixed amount per block rather than an average of their random roll.
- If the hut inventory is full, the rest of that day's payout is not produced. The mine is not a stockpile.
- The survey starts at the band the ladder top sits in, so ore above the hut is not counted.
- Nothing scales output by the miner's skill or the number of miners. Output is purely ground times depth.
  That may be wrong for a colony game where workers are meant to matter.

**Fixed during the work:**
- `onWakeUp` is not a daily hook. It fires from `CitizenSleepHandler`, once per assigned citizen that
  actually slept, so it is neither once per day nor reliable when nobody sleeps. Uses the colony day counter
  instead.
- `getLadderLocation()` returns null rather than a fallback whenever the hut has not been built far enough
  for its ladder to be tagged. Both `getShaftDepth` and the survey would have thrown on a fresh mine.
- The first version re-walked the whole covered area every colony tick, plus a recursive walk down the
  ladder, forever, for nothing.

---

## Repo notes

- `origin` → `ldtteam/minecolonies`, pull from here.
- `fork` → `magnus-trent/dwarfcolonies`, push here. `version/main` was force-updated to upstream on
  2026-09-24; its previous tip was 1.19-era upstream history with nothing local in it.
- These commits sit on `version/1.21`, which tracks `origin`. Worth moving to a feature branch if upstream
  pulls are expected, since upstream history will run into them.
