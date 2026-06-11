package com.yungnickyoung.minecraft.bettercaves.util;

import com.yungnickyoung.minecraft.bettercaves.config.Configuration;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraftforge.common.BiomeDictionary;

/**
 * Utility functions for Better Caves.
 * This class may not be instantiated - all members are {@code public} and {@code static},
 * and as such may be accessed freely.
 */
public class BetterCavesUtils {
    private BetterCavesUtils() {} // Private constructor prevents instantiation

    /**
     * Returns the y-coordinate of the surface block for a given local block coordinate for a given chunk.
     * Note that water blocks also count as the surface.
     * @param primer primer for chunk
     * @param localX The block's chunk-local x-coordinate
     * @param localZ The block's chunk-local z-coordinate
     * @return The y-coordinate of the surface block
     */
    public static int getSurfaceAltitudeForColumn(ChunkPrimer primer, int localX, int localZ) {
        return searchSurfaceAltitudeInRangeForColumn(primer, localX, localZ, 255, 0);
    }

    /**
     * Searches for the y-coordinate of the surface block for a given local block coordinate for a given chunk in a
     * specific range of y-coordinates.
     * Note that water blocks also count as the surface.
     * @param primer primer for chunk
     * @param localX The block's chunk-local x-coordinate
     * @param localZ The block's chunk-local z-coordinate
     * @param topY The top y-coordinate to stop searching at
     * @param bottomY The bottom y-coordinate to start searching at
     * @return The y-coordinate of the surface block
     */
    public static int searchSurfaceAltitudeInRangeForColumn(ChunkPrimer primer, int localX, int localZ, int topY, int bottomY) {
        for (int y = topY; y >= bottomY; y--) {
            IBlockState blockState = primer.getBlockState(localX, y, localZ);
            if (blockState != Blocks.AIR.getDefaultState() && blockState.getMaterial() != Material.WATER)
                return Math.min(y + 1, topY);
        }

        return 1; // Surface somehow not found
    }

    public static String dimensionAsString(int dimensionID, String dimensionName) {
        return String.format("%d (%s)", dimensionID, dimensionName);
    }

    public static int getLocal(int coordinate) {
        return coordinate & 0xF; // This is same as modulo 16, but quicker
    }

    /**
     * @return true if the provided dimension ID is whitelisted in the config
     */
    public static boolean isDimensionWhitelisted(int dimID) {
        // Ignore the dimension ID list if global whitelisting is enabled
        if (Configuration.enableGlobalWhitelist)
            return true;

        for (int dim : Configuration.whitelistedDimensionIDs)
            if (dimID == dim) return true;

        return false;
    }

    public static boolean[] getOceanMaskFlat(World world, int chunkX, int chunkZ, int border) {
        int width = 16 + border * 2;
        boolean[] oceanMask = new boolean[width * width];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int startX = chunkX * 16 - border;
        int startZ = chunkZ * 16 - border;

        for (int localX = 0; localX < width; localX++) {
            for (int localZ = 0; localZ < width; localZ++) {
                pos.setPos(startX + localX, 1, startZ + localZ);
                oceanMask[localX * width + localZ] = BiomeDictionary.hasType(world.getBiome(pos), BiomeDictionary.Type.OCEAN);
            }
        }

        return oceanMask;
    }

    public static float biomeDistanceFactor(int localX, int localZ, int radius, boolean[] oceanMask, int width, boolean targetIsOcean) {
        int centerX = localX + radius;
        int centerZ = localZ + radius;

        for (int i = 1; i <= radius; i++) {
            for (int j = 0; j <= i; j++) {
                for (EnumFacing direction : EnumFacing.Plane.HORIZONTAL) {
                    int checkX = centerX + direction.getXOffset() * i + direction.rotateY().getXOffset() * j;
                    int checkZ = centerZ + direction.getZOffset() * i + direction.rotateY().getZOffset() * j;
                    if (oceanMask[checkX * width + checkZ] == targetIsOcean) {
                        return (float)(i + j) / (2 * radius);
                    }
                    if (j != 0 && i != j) {
                        checkX = centerX + direction.getXOffset() * i + direction.rotateYCCW().getXOffset() * j;
                        checkZ = centerZ + direction.getZOffset() * i + direction.rotateYCCW().getZOffset() * j;
                        if (oceanMask[checkX * width + checkZ] == targetIsOcean) {
                            return (float)(i + j) / (2 * radius);
                        }
                    }
                }
            }
        }

        return 1;
    }
}
