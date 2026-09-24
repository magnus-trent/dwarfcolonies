# Handoff — multi-worker builders

Status as of commit `3077d34d2d` on `version/1.21`.

**Compiled and built, never run.** This repo has no test source set (`src/` holds only `main` and
`datagen`; `:test` reports `NO-SOURCE`). `./gradlew build` passes, which covers compilation and
datagen only. Nothing described below has executed even once. Treat every behavioral claim here as
intent, not as fact.

---

## Goal

One builder's hut should be able to staff a crew and put all of them on the same structure, so that
building can be accelerated by staffing rather than only by placing more huts. Worker capacity should
scale with hut level the way a residence's occupancy does.

Scope taken was the **builder only**. Miner, lumberjack, quarrier, and every other hut are untouched
and still take one worker.

## What was there before

A work order was owned by exactly one hut, enforced independently in four places:

1. `IWorkOrder.getClaimedBy()/setClaimedBy()` — a single `BlockPos`.
2. `AbstractBuildingStructureBuilder.workOrderId` — a single `int` on the hut.
3. `WorkManager.tryAssignWorkOrder()` — skips huts where `hasWorkOrder()`, returns on first match.
4. `BuildingModules.BUILDER_WORK` registered with `(b) -> 1`.

Points 1–3 were left alone: one hut still claims one work order. Only point 4 changed, plus
everything downstream of it that assumed the hut held a single worker.

## Design

### Lanes, not ranges

The first attempt carved contiguous ranges of iterator positions per worker. **That approach is dead,
and it is worth knowing why before anyone tries it again.**

`StructurePlacer.executeStructureStep` seeds the iterator once on entry and then loops internally with
no stop boundary — its only exits are the placement budget, the blocks-checked limit, or end of
structure. A worker handed a range runs straight out the far end of it into the next worker's
territory. The overrun cannot be detected by comparing positions either, because the iterator strategy
is player-selectable (`BuilderModeSetting` exposes all of `StructureIterators.getKeySet()`), and under
`random` there is no ordering to compare against.

What replaced it: each worker gets a **lane**, a residue class over a coarse horizontal grid
(`(x>>2, z>>2)` hashed modulo the number of workers, on **world** positions so every worker computes
the same split). The skip predicate handed to `increment`/`decrement` is evaluated per position, so a
worker simply skips everything outside its own lane. This assumes no ordering, so it holds for every
iterator strategy. Cells are coarse so a worker's share stays clustered and it is not walking back and
forth across the site.

### Coverage is the sweep, not the lanes

Lanes divide labour. They do **not** guarantee coverage: a worker that dies or is reassigned mid-stage
takes its share of the structure with it, and the stage would otherwise finish with holes.

Coverage comes from a final sweep. Once every lane reports it reached the end of the stage, one worker
walks the stage again with no lane filter. Placement already skips positions that are already correct,
so the sweep is cheap. When only one lane was ever handed out the sweep is skipped, because a single
lane already covered everything — this is what keeps the single-worker path identical to before.

**If you change anything here, keep that separation.** Lanes are a performance split; the sweep is the
correctness guarantee. Lane reshuffling mid-stage (a worker joining or leaving, which shifts ownership
for everyone) is safe precisely because the sweep backstops it.

### Stage barrier

Stages are structure-global and ordered — CLEAR before BUILD_SOLID before DECORATE before SPAWN — and
that order is load-bearing for block support. The scheduler will not hand out a lane in the next stage
until the current one is swept. A worker asking for the wrong stage is told to wait.

## Files

| File | What it does |
|---|---|
| `core/colony/buildings/utils/BuildWorkScheduler.java` | New. Per-building. Hands out lanes, holds the stage barrier, issues the sweep. Persists only the stage; lanes are rebuilt on load and the sweep covers anything a dropped lane missed. |
| `core/colony/buildings/utils/WorkerCapacity.java` | New. `single()` and `perLevel(n)` capacity functions, so extending this to other huts is a one-line change each. |
| `core/colony/buildings/AbstractBuildingStructureBuilder.java` | Owns the scheduler; resets it on work-order change; serializes it; exposes the crew in `serializeToView`. |
| `core/entity/ai/workers/AbstractEntityAIStructure.java` | Acquires a lane each `structureStep`, wraps all eight stage skip-predicates in `withinLane(...)`, gates the stage advance behind the scheduler, tracks a per-worker cursor. |
| `core/colony/buildings/modules/BuildingResourcesModule.java` | Bucket sizing measured against the crew rather than one worker. |
| `core/colony/buildings/modules/BuildingModules.java` | `BUILDER_WORK` now `WorkerCapacity.perLevel(2)` → 2/4/6/8/10 by hut level. |
| `core/colony/buildings/views/AbstractBuildingBuilderView.java` | Holds the whole crew's names. `getWorkerName()` joins them so existing callers keep working. |

## Two bugs already found and fixed

Recorded because both were introduced during this work and caught on self-review, not by any test:

1. The lane was acquired using the worker's **stale local stage**, before syncing from
   `building.getProgress()`. A worker could ask against a stage another worker had already finished.
   The sync now happens before the acquire.
2. Each worker was seeded from the building's **shared** `progressPos`, which every worker overwrites
   on every step — two builders would have yanked each other's cursor around the structure. Each
   worker now keeps `laneProgress`/`laneStage`; the shared position is still written for saving and
   for the hut display.

## Verify this first, in this order

1. **Single builder is unchanged.** One builder, one work order, start to finish. This is the
   regression that matters most — the sweep should be skipped and behavior should be identical to
   before. If this is broken, nothing else is worth looking at.
2. **Two builders, one work order.** Level a builder's hut to 2. Watch for: both actually placing
   blocks, no two workers fighting over one position, and the build completing.
3. **Holes.** Compare the finished structure against the blueprint. Holes mean the sweep is not
   closing gaps, or lane ownership shifted without the sweep catching it.
4. **Stalls.** A build that stops partway most likely means the stage barrier never released — a lane
   that never reported exhausted, or a sweep that was never issued. `BuildWorkScheduler.getLaneCount()`
   and `getStage()` are there for exactly this.
5. **Save and reload mid-build.** Lanes are deliberately not persisted; only the stage is. The crew
   should re-acquire and carry on.

## Known gaps, not guesses

- **`CLEAR_WATER` cannot be laned.** `placer.clearWaterStep()` takes no predicate, so every worker runs
  it over the whole structure. Idempotent, so correct, but redundant work.
- **One bucket for the crew.** Bucket *size* now scales with crew size, so trip count no longer
  multiplies. But it is still a single shared bucket, so material *throughput* is not parallel. Whether
  that should become per-worker buckets is a gameplay decision, still open.
- **Barrier spin.** A worker waiting at a stage boundary loops through `structureStep` returning
  `getState()`. Correct, mildly wasteful.
- **Auto-hire pulls harder now.** `WorkerBuildingModule.onColonyTick` fills toward `isFull()`, so
  raising builder capacity to 2/level draws jobless citizens in faster, and `calculateMaxCitizens` ties
  back to housing. The staffing curve is also a colony population and food-economy change.

## If you extend this to other huts

The scheduler is not builder-specific in shape, but it is blueprint-specific in substance: a lane is a
share of *blueprint positions*. Huts whose work is an external target — miner nodes, lumberjack trees,
fisherman ponds — each hold their own version of this collision, and their state layouts were **not**
examined. Do not assume they look like the builder. The reusable piece across all of them is the
interface: the building owns available work, a set of claims, and a completion signal; the AI asks
instead of assuming.

`WorkerCapacity.perLevel(n)` is ready for whatever curve each role should get, but raising a hut's
capacity before its AI stops assuming one worker will produce workers that trip over each other.

## Repo

- `origin` → `ldtteam/minecolonies` (upstream; pull from here)
- `fork` → `magnus-trent/dwarfcolonies` (push here). `version/main` was force-updated to upstream on
  2026-09-24; its previous tip was 1.19-era upstream history with nothing local in it.
- This commit sits on `version/1.21`, which tracks `origin`. Worth moving to a feature branch if
  upstream pulls are expected.
