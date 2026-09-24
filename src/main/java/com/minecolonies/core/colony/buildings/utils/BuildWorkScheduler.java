package com.minecolonies.core.colony.buildings.utils;

import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Splits the structure of a single work order between the workers of one building, so more than one of them can build it
 * at once without placing on top of each other.
 * <p>
 * Each worker is handed a {@link Lane}: a share of the structure defined by a residue class over a coarse horizontal
 * grid. A worker skips every position outside its own lane, so the lanes never overlap no matter which iterator strategy
 * the work order uses - including the ones with no meaningful position ordering, such as {@code random}.
 * <p>
 * Lanes are only a division of labour, never a guarantee of coverage: a worker that dies or is reassigned mid-stage
 * takes its share of the structure with it. Coverage is guaranteed separately, by a final sweep - once every lane has
 * reported that it reached the end of the stage, one worker walks the whole stage again with no lane filter, which fills
 * anything the lanes missed. Placement skips positions that are already correct, so the sweep is cheap.
 * <p>
 * Stages are structure-global and ordered (clear before solid before decorate before spawn), so no worker is given a
 * lane in the next stage until the current one has been swept. With a single worker the lane covers everything and the
 * sweep is skipped, which leaves the original single-worker behaviour untouched.
 */
public class BuildWorkScheduler
{
    /**
     * NBT tags.
     */
    private static final String TAG_SCHEDULER_STAGE = "schedulerStage";
    private static final String TAG_SCHEDULER_ORDER = "schedulerOrder";

    /**
     * Width, in blocks, of the grid cells that lanes are handed out in. Lanes own whole cells rather than single
     * positions so that a worker's share of the structure stays in one area and it does not spend its time walking
     * across the build site.
     */
    private static final int LANE_CELL_SHIFT = 2;

    /**
     * A worker's share of the structure.
     *
     * @param index the lane's own number, below {@code total}.
     * @param total how many lanes the structure is split between.
     * @param sweep when true the lane covers the whole structure, used for the final coverage pass.
     */
    public record Lane(int index, int total, boolean sweep)
    {
        /**
         * Whether a position belongs to this lane.
         *
         * @param worldPos the position in the world. Every worker on the structure sees the same world positions, so
         *                 the split is the same for all of them.
         * @return true when the holder of this lane should work this position.
         */
        public boolean owns(@NotNull final BlockPos worldPos)
        {
            if (sweep || total <= 1)
            {
                return true;
            }
            final int cell = (worldPos.getX() >> LANE_CELL_SHIFT) * 31 + (worldPos.getZ() >> LANE_CELL_SHIFT);
            return Math.floorMod(cell, total) == index;
        }
    }

    /**
     * The work order the current lanes belong to. Everything is dropped when it changes.
     */
    private int workOrderId = 0;

    /**
     * The stage every current lane belongs to.
     */
    @Nullable
    private BuildingProgressStage stage = null;

    /**
     * The lane number held by each worker.
     */
    private final Map<Integer, Integer> lanes = new HashMap<>();

    /**
     * The workers that have reached the end of the stage within their own lane.
     */
    private final Set<Integer> exhausted = new HashSet<>();

    /**
     * The most lanes handed out at once during this stage. The final sweep is only needed when the structure was ever
     * split, since a single lane already covers everything.
     */
    private int laneHighWaterMark = 0;

    /**
     * The worker doing the final sweep, or -1 when no sweep is in progress.
     */
    private int sweepingCitizen = -1;

    /**
     * Whether the stage has been fully covered and may advance.
     */
    private boolean stageDrained = false;

    /**
     * Point the scheduler at a work order. Resets everything when it is a different one to the one held.
     *
     * @param newWorkOrderId the work order the building is now building.
     */
    public void setWorkOrder(final int newWorkOrderId)
    {
        if (this.workOrderId != newWorkOrderId)
        {
            this.workOrderId = newWorkOrderId;
            reset();
        }
    }

    /**
     * Drop all lanes and stage progress.
     */
    public void reset()
    {
        this.stage = null;
        this.lanes.clear();
        this.exhausted.clear();
        this.laneHighWaterMark = 0;
        this.sweepingCitizen = -1;
        this.stageDrained = false;
    }

    /**
     * Ask for a share of the structure to work on.
     * <p>
     * Returns null when the worker must wait: either the stage it asked for is not the one the other workers are still
     * finishing, or the stage is already covered and is waiting to advance.
     *
     * @param citizenId  the worker asking.
     * @param askedStage the stage the worker is ready to work on.
     * @return the lane to work, or null when the worker has nothing to do right now.
     */
    @Nullable
    public Lane acquire(final int citizenId, @Nullable final BuildingProgressStage askedStage)
    {
        if (askedStage == null)
        {
            return null;
        }

        if (stage != askedStage)
        {
            // Hold the worker back until everybody still in the old stage has finished with it.
            if (stage != null && !stageDrained && !lanes.isEmpty())
            {
                return null;
            }
            openStage(askedStage);
        }

        if (stageDrained)
        {
            return null;
        }

        if (sweepingCitizen != -1)
        {
            // A sweep is running; only the worker doing it has anything left to do in this stage.
            return sweepingCitizen == citizenId ? new Lane(0, 1, true) : null;
        }

        final Integer existing = lanes.get(citizenId);
        if (existing != null)
        {
            if (exhausted.contains(citizenId))
            {
                return beginSweepIfReady(citizenId);
            }
            return new Lane(existing, Math.max(lanes.size(), 1), false);
        }

        final int index = nextFreeLane();
        lanes.put(citizenId, index);
        laneHighWaterMark = Math.max(laneHighWaterMark, lanes.size());
        return new Lane(index, Math.max(lanes.size(), 1), false);
    }

    /**
     * Report that a worker reached the end of the stage within its lane.
     *
     * @param citizenId the worker reporting.
     */
    public void reportExhausted(final int citizenId)
    {
        if (citizenId == sweepingCitizen)
        {
            sweepingCitizen = -1;
            stageDrained = true;
            return;
        }
        if (lanes.containsKey(citizenId))
        {
            exhausted.add(citizenId);
        }
    }

    /**
     * Give up a lane, because the worker stopped building. Its share of the structure is left to the final sweep.
     *
     * @param citizenId the worker leaving.
     */
    public void release(final int citizenId)
    {
        lanes.remove(citizenId);
        exhausted.remove(citizenId);
        if (sweepingCitizen == citizenId)
        {
            sweepingCitizen = -1;
        }
    }

    /**
     * Whether the current stage has been fully covered and the building may move to the next one.
     *
     * @return true when the stage is done.
     */
    public boolean isStageDrained()
    {
        return stageDrained;
    }

    /**
     * Tell the scheduler the building has moved on, so the next stage starts clean.
     */
    public void onStageAdvanced()
    {
        reset();
    }

    /**
     * The stage the current lanes belong to.
     *
     * @return the stage, or null when nothing has been handed out yet.
     */
    @Nullable
    public BuildingProgressStage getStage()
    {
        return stage;
    }

    /**
     * How many workers hold a lane right now.
     *
     * @return the number of lanes handed out.
     */
    public int getLaneCount()
    {
        return lanes.size();
    }

    /**
     * Start a new stage, dropping everything the previous one held.
     *
     * @param newStage the stage to open.
     */
    private void openStage(@NotNull final BuildingProgressStage newStage)
    {
        this.stage = newStage;
        this.lanes.clear();
        this.exhausted.clear();
        this.laneHighWaterMark = 0;
        this.sweepingCitizen = -1;
        this.stageDrained = false;
    }

    /**
     * Once every lane has reached the end of the stage, hand the asking worker the sweep that closes any gaps the lanes
     * left behind. When the structure was never split there is nothing a sweep could find, so the stage is simply done.
     *
     * @param citizenId the worker asking for more work.
     * @return the sweep lane, or null when the stage is finished or others are still working.
     */
    @Nullable
    private Lane beginSweepIfReady(final int citizenId)
    {
        if (exhausted.size() < lanes.size())
        {
            return null;
        }

        if (laneHighWaterMark <= 1)
        {
            stageDrained = true;
            return null;
        }

        sweepingCitizen = citizenId;
        return new Lane(0, 1, true);
    }

    /**
     * Find the lowest lane number nobody holds.
     *
     * @return the lane number to hand out.
     */
    private int nextFreeLane()
    {
        int index = 0;
        while (lanes.containsValue(index))
        {
            index++;
        }
        return index;
    }

    /**
     * Write the stage out. Lanes are deliberately not written: on load every worker asks again and the structure is
     * split afresh, and anything a dropped lane would have missed is caught by the sweep.
     *
     * @param compound the tag to write to.
     */
    public void serializeNBT(@NotNull final CompoundTag compound)
    {
        if (stage == null)
        {
            return;
        }
        compound.putInt(TAG_SCHEDULER_ORDER, workOrderId);
        compound.putString(TAG_SCHEDULER_STAGE, stage.name());
    }

    /**
     * Read the stage back.
     *
     * @param compound the tag to read from.
     */
    public void deserializeNBT(@NotNull final CompoundTag compound)
    {
        reset();
        if (!compound.contains(TAG_SCHEDULER_STAGE))
        {
            return;
        }
        this.workOrderId = compound.getInt(TAG_SCHEDULER_ORDER);
        try
        {
            this.stage = BuildingProgressStage.valueOf(compound.getString(TAG_SCHEDULER_STAGE));
        }
        catch (final IllegalArgumentException e)
        {
            this.stage = null;
        }
    }
}
