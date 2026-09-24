package com.minecolonies.core.colony.buildings.views;

import com.minecolonies.api.colony.IColonyView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provides a view of the builder building class.
 */
public abstract class AbstractBuildingBuilderView extends AbstractBuildingView
{
    /**
     * The names of the workers at this building, in assignment order.
     */
    private List<String> workerNames = Collections.emptyList();

    /**
     * Public constructor of the view, creates an instance of it.
     *
     * @param c the colony.
     * @param l the position.
     */
    public AbstractBuildingBuilderView(final IColonyView c, final BlockPos l)
    {
        super(c, l);
    }

    @Override
    public void deserialize(@NotNull final RegistryFriendlyByteBuf buf)
    {
        super.deserialize(buf);
        final int count = buf.readInt();
        final List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            names.add(buf.readUtf(32767));
        }
        workerNames = names;
    }

    /**
     * Get the names of every worker assigned to this building.
     *
     * @return the names, empty when nobody is assigned.
     */
    public List<String> getWorkerNames()
    {
        return Collections.unmodifiableList(workerNames);
    }

    /**
     * Get the workers assigned to this building as one line of text, for display where a single name used to be shown.
     *
     * @return the names separated by commas, or an empty string when nobody is assigned.
     */
    public String getWorkerName()
    {
        return String.join(", ", workerNames);
    }
}
