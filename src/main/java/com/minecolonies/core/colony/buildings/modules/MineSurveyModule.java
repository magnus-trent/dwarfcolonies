package com.minecolonies.core.colony.buildings.modules;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingMiner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out what a mine is sitting on top of, and pays it out daily.
 * <p>
 * The mine no longer digs ore out of the ground a block at a time. Instead it surveys the ground it covers and
 * reproduces what it finds: the ore in the world is left where it is and is read only as a measure of how rich the
 * ground is. A mine therefore never exhausts its ground, and the way to make it produce more is to make it cover more
 * ground, by upgrading the hut, or more depth, by keeping it supplied so the shaft can keep descending.
 * <p>
 * The area covered is a square of chunks around the hut, one chunk of radius per hut level: a level one mine covers the
 * three-by-three block of chunks around itself, a level five mine covers eleven by eleven.
 * <p>
 * Depth is counted in bands of {@value #BAND_HEIGHT} blocks, and the bands accumulate: a mine that has reached down
 * through four bands pays out from all four, so descending only ever adds to what it makes.
 * <p>
 * Surveying is done once per chunk and band and then remembered, because a level five mine covers 121 chunks and
 * reading a whole band of those is half a million block lookups. Work is spread over colony ticks, and a chunk nobody
 * has loaded is simply left for a later tick rather than being forced into memory.
 */
public class MineSurveyModule extends AbstractBuildingModule implements IPersistentModule, ITickingModule
{
    /**
     * How tall one band of depth is.
     */
    public static final int BAND_HEIGHT = 16;

    /**
     * The share of the surveyed ore that is produced each day.
     * <p>
     * Hardcoded for now, deliberately: it is the one number in here that wants to be judged by playing rather than by
     * reasoning, and it has not been played yet.
     */
    public static final double DAILY_YIELD_FRACTION = 0.02;

    /**
     * How many chunk bands to survey per colony tick. Surveying only happens while the shaft is descending into new
     * ground, so this is a short burst rather than steady work, but a whole band at once would be a visible stall.
     */
    private static final int SURVEYS_PER_TICK = 4;

    /**
     * NBT tags.
     */
    private static final String TAG_SURVEYED    = "surveyedCells";
    private static final String TAG_ORE_COUNTS  = "surveyedOres";
    private static final String TAG_ORE_NAME    = "ore";
    private static final String TAG_ORE_AMOUNT  = "amount";
    private static final String TAG_LAST_DAY    = "lastYieldDay";

    /**
     * One chunk's worth of one band, the unit that gets surveyed and remembered.
     *
     * @param chunkX the chunk's x.
     * @param chunkZ the chunk's z.
     * @param band   the band index, {@link #BAND_HEIGHT} blocks tall, counted from y zero.
     */
    private record SurveyCell(int chunkX, int chunkZ, int band)
    {
    }

    /**
     * Every chunk band that has already been counted.
     */
    private final Set<SurveyCell> surveyed = new HashSet<>();

    /**
     * How much of each ore the surveyed ground holds, accumulated across every band counted so far.
     */
    private final Map<Block, Integer> oreCounts = new LinkedHashMap<>();

    /**
     * The colony day the last payout was made on, so a day pays out once.
     */
    private int lastYieldDay = -1;

    /**
     * The ground the mine covered the last time surveying found nothing left to do, as radius, top band and reached
     * band. While the mine still covers exactly that, there is no point walking the whole area again every tick, which
     * for a level five mine is a couple of thousand lookups and a walk down the ladder for nothing.
     */
    private int[] surveyedUpTo = null;

    /**
     * The band a given height falls in.
     *
     * @param y the height.
     * @return the band index.
     */
    private static int bandOf(final int y)
    {
        return Math.floorDiv(y, BAND_HEIGHT);
    }

    /**
     * How many chunks out from the hut the mine draws from, one per hut level.
     *
     * @return the radius in chunks, zero when the hut is not built yet.
     */
    public int getChunkRadius()
    {
        return Math.max(building.getBuildingLevel(), 0);
    }

    /**
     * What the mine holds a survey of, for display.
     *
     * @return the ore counts accumulated so far.
     */
    @NotNull
    public Map<Block, Integer> getOreCounts()
    {
        return new LinkedHashMap<>(oreCounts);
    }

    /**
     * How many chunk bands have been counted so far, for display and debugging.
     *
     * @return the number of surveyed cells.
     */
    public int getSurveyedCellCount()
    {
        return surveyed.size();
    }

    @Override
    public void onColonyTick(@NotNull final IColony colony)
    {
        if (!(building instanceof final BuildingMiner mine) || building.getBuildingLevel() < 1)
        {
            return;
        }

        final Level world = colony.getWorld();
        if (world == null)
        {
            return;
        }

        surveySomeGround(mine, world);
        payOutTheDay(colony);
    }

    /**
     * Count ore in a few of the chunk bands the mine covers but has not looked at yet.
     *
     * @param mine  the mine.
     * @param world the world to read.
     */
    private void surveySomeGround(@NotNull final BuildingMiner mine, @NotNull final Level world)
    {
        final BlockPos ladder = mine.getLadderLocation();
        if (ladder == null)
        {
            // No ladder yet, so no shaft and nothing to measure depth against.
            return;
        }

        final int topBand = bandOf(ladder.getY());
        final int reachedBand = bandOf(mine.getShaftDepth(world));
        final int radius = getChunkRadius();

        final int[] ground = {radius, topBand, reachedBand};
        if (surveyedUpTo != null && Arrays.equals(surveyedUpTo, ground))
        {
            return;
        }

        final ChunkPos center = new ChunkPos(building.getPosition());

        int budget = SURVEYS_PER_TICK;
        boolean anyLeft = false;
        for (int band = topBand; band >= reachedBand && budget > 0; band--)
        {
            for (int cx = center.x - radius; cx <= center.x + radius && budget > 0; cx++)
            {
                for (int cz = center.z - radius; cz <= center.z + radius && budget > 0; cz++)
                {
                    final SurveyCell cell = new SurveyCell(cx, cz, band);
                    if (surveyed.contains(cell))
                    {
                        continue;
                    }

                    final BlockPos probe = new BlockPos(cx << 4, band * BAND_HEIGHT, cz << 4);
                    if (!WorldUtil.isBlockLoaded(world, probe))
                    {
                        // Nobody has this chunk loaded. Leave it for a tick when they do rather than forcing it in.
                        anyLeft = true;
                        continue;
                    }

                    surveyCell(world, cell);
                    surveyed.add(cell);
                    budget--;
                    markDirty();
                }
            }
        }

        // Only rest once the whole area came up already counted. An unloaded chunk counts as work still outstanding, so
        // it gets picked up on a later tick instead of being written off.
        surveyedUpTo = (anyLeft || budget < SURVEYS_PER_TICK) ? null : ground;
    }

    /**
     * Count the ore in one chunk band and fold it into the running totals.
     *
     * @param world the world to read.
     * @param cell  the chunk band to count.
     */
    private void surveyCell(@NotNull final Level world, @NotNull final SurveyCell cell)
    {
        final int baseX = cell.chunkX() << 4;
        final int baseZ = cell.chunkZ() << 4;
        final int baseY = cell.band() * BAND_HEIGHT;
        final int minY = Math.max(baseY, world.getMinBuildHeight());
        final int maxY = Math.min(baseY + BAND_HEIGHT, world.getMaxBuildHeight());

        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = minY; y < maxY; y++)
        {
            for (int x = 0; x < 16; x++)
            {
                for (int z = 0; z < 16; z++)
                {
                    pos.set(baseX + x, y, baseZ + z);
                    final BlockState state = world.getBlockState(pos);
                    if (state.isAir())
                    {
                        continue;
                    }
                    if (IColonyManager.getInstance().getCompatibilityManager().isOre(state))
                    {
                        oreCounts.merge(state.getBlock(), 1, Integer::sum);
                    }
                }
            }
        }
    }

    /**
     * Produce a day's worth of ore, once per colony day.
     *
     * @param colony the colony, for the day count and the world.
     */
    private void payOutTheDay(@NotNull final IColony colony)
    {
        final int today = colony.getDay();
        if (today == lastYieldDay)
        {
            return;
        }
        lastYieldDay = today;
        markDirty();

        if (oreCounts.isEmpty() || !(colony.getWorld() instanceof final ServerLevel serverLevel))
        {
            return;
        }

        for (final Map.Entry<Block, Integer> entry : oreCounts.entrySet())
        {
            final int amount = (int) Math.floor(entry.getValue() * DAILY_YIELD_FRACTION);
            if (amount < 1)
            {
                continue;
            }

            final ItemStack drop = dropOf(entry.getKey(), serverLevel);
            if (drop.isEmpty())
            {
                continue;
            }

            int left = amount;
            while (left > 0)
            {
                final ItemStack batch = drop.copy();
                batch.setCount(Math.min(left, drop.getMaxStackSize()));
                left -= batch.getCount();
                if (!InventoryUtils.addItemStackToProvider(building, batch))
                {
                    // The hut is full. Whatever is left over is simply not produced; the mine is not a stockpile.
                    return;
                }
            }
        }
    }

    /**
     * What one of an ore block yields.
     * <p>
     * The block's own loot table is asked once and the result is used as the unit of production. Ores whose tables roll
     * a variable count, such as redstone and lapis, therefore pay out a fixed amount per block rather than an average
     * of their roll, which is a simplification worth knowing about if the numbers ever look off.
     *
     * @param ore         the ore block.
     * @param serverLevel the level, needed to read a loot table.
     * @return the stack one block of it yields, empty when it yields nothing.
     */
    @NotNull
    private ItemStack dropOf(@NotNull final Block ore, @NotNull final ServerLevel serverLevel)
    {
        try
        {
            final List<ItemStack> drops = Block.getDrops(ore.defaultBlockState(), serverLevel, building.getPosition(), null);
            for (final ItemStack stack : drops)
            {
                if (!stack.isEmpty())
                {
                    return stack;
                }
            }
        }
        catch (final Exception e)
        {
            Log.getLogger().warn("Could not work out what " + BuiltInRegistries.BLOCK.getKey(ore) + " drops; the mine will not produce it.", e);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void deserializeNBT(@NotNull final HolderLookup.Provider provider, final CompoundTag compound)
    {
        surveyed.clear();
        oreCounts.clear();

        final int[] cells = compound.getIntArray(TAG_SURVEYED);
        for (int i = 0; i + 2 < cells.length; i += 3)
        {
            surveyed.add(new SurveyCell(cells[i], cells[i + 1], cells[i + 2]));
        }

        final ListTag ores = compound.getList(TAG_ORE_COUNTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < ores.size(); i++)
        {
            final CompoundTag tag = ores.getCompound(i);
            final ResourceLocation key = ResourceLocation.tryParse(tag.getString(TAG_ORE_NAME));
            if (key == null || !BuiltInRegistries.BLOCK.containsKey(key))
            {
                // A block from a mod that is no longer present. Drop it rather than failing the whole load.
                continue;
            }
            oreCounts.put(BuiltInRegistries.BLOCK.get(key), tag.getInt(TAG_ORE_AMOUNT));
        }

        lastYieldDay = compound.contains(TAG_LAST_DAY) ? compound.getInt(TAG_LAST_DAY) : -1;
    }

    @Override
    public void serializeNBT(@NotNull final HolderLookup.Provider provider, final CompoundTag compound)
    {
        final int[] cells = new int[surveyed.size() * 3];
        int i = 0;
        for (final SurveyCell cell : surveyed)
        {
            cells[i++] = cell.chunkX();
            cells[i++] = cell.chunkZ();
            cells[i++] = cell.band();
        }
        compound.putIntArray(TAG_SURVEYED, cells);

        final ListTag ores = new ListTag();
        for (final Map.Entry<Block, Integer> entry : oreCounts.entrySet())
        {
            final CompoundTag tag = new CompoundTag();
            tag.putString(TAG_ORE_NAME, BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString());
            tag.putInt(TAG_ORE_AMOUNT, entry.getValue());
            ores.add(tag);
        }
        compound.put(TAG_ORE_COUNTS, ores);

        compound.putInt(TAG_LAST_DAY, lastYieldDay);
    }
}
