package com.minecolonies.core.colony.buildings.utils;

import com.minecolonies.api.colony.buildings.IBuilding;

import java.util.function.Function;

/**
 * How many workers a building takes, as a function of how far it has been built up.
 * <p>
 * Buildings that can put more than one worker to use grow their crew as they are upgraded, the same way a residence
 * houses one more citizen per level. An unbuilt building has a level of zero and so takes nobody.
 */
public final class WorkerCapacity
{
    private WorkerCapacity()
    {
    }

    /**
     * A single worker at any level, which is what most buildings take.
     *
     * @return the capacity function.
     */
    public static Function<IBuilding, Integer> single()
    {
        return b -> 1;
    }

    /**
     * A crew that grows by {@code perLevel} workers for every level the building has been upgraded: at two per level
     * that is 2, 4, 6, 8, 10 across the five levels.
     *
     * @param perLevel how many workers each level is worth.
     * @return the capacity function.
     */
    public static Function<IBuilding, Integer> perLevel(final int perLevel)
    {
        return b -> perLevel * b.getBuildingLevel();
    }
}
