package com.yungnickyoung.minecraft.bettercaves.world;

import com.yungnickyoung.minecraft.bettercaves.config.BCSettings;
import com.yungnickyoung.minecraft.bettercaves.config.util.ConfigHolder;
import com.yungnickyoung.minecraft.bettercaves.util.BetterCavesUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.util.math.BlockPos;

/**
 * Per-chunk data shared by all Better Caves carving passes.
 */
public class ChunkCaveContext {
    private static final int CHUNK_SIZE = 16;
    private static final int COLUMN_COUNT = CHUNK_SIZE * CHUNK_SIZE;
    private static final int OCEAN_MASK_BORDER = 2;

    private final ChunkPrimer primer;
    private final int chunkX;
    private final int chunkZ;
    private final int baseBlockX;
    private final int baseBlockZ;
    private final int[] surfaceAltitudes;
    private final IBlockState[] liquidBlocks;
    private final boolean[] oceanMask;
    private final int oceanMaskWidth;
    private final boolean[] oceanColumns;
    private final boolean[] oceanSurroundedColumns;
    private final float[] biomeDistanceFactors;
    private final Biome[] biomes;

    public ChunkCaveContext(World world, ChunkPrimer primer, int chunkX, int chunkZ, ConfigHolder config,
                            WaterRegionController waterRegionController, boolean needsSurfaceAltitudes,
                            boolean needsOceanMask, boolean needsBiomeDistanceFactors, boolean needsLiquidBlocks,
                            boolean needsBiomeCache, int surfaceSearchTopY) {
        this.primer = primer;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.baseBlockX = chunkX * CHUNK_SIZE;
        this.baseBlockZ = chunkZ * CHUNK_SIZE;
        this.surfaceAltitudes = needsSurfaceAltitudes
            ? buildSurfaceAltitudes(primer, config.overrideSurfaceDetection.get(), surfaceSearchTopY)
            : null;
        this.oceanMask = needsOceanMask
            ? BetterCavesUtils.getOceanMaskFlat(world, chunkX, chunkZ, OCEAN_MASK_BORDER)
            : null;
        this.oceanMaskWidth = CHUNK_SIZE + OCEAN_MASK_BORDER * 2;
        if (this.oceanMask != null) {
            this.oceanColumns = new boolean[COLUMN_COUNT];
            this.oceanSurroundedColumns = new boolean[COLUMN_COUNT];
            this.biomeDistanceFactors = needsBiomeDistanceFactors ? new float[COLUMN_COUNT] : null;
            buildOceanColumnFields();
        }
        else {
            this.oceanColumns = null;
            this.oceanSurroundedColumns = null;
            this.biomeDistanceFactors = null;
        }
        this.liquidBlocks = needsLiquidBlocks
            ? waterRegionController.getLiquidBlocksFlatForChunk(chunkX, chunkZ)
            : null;
        this.biomes = needsBiomeCache ? buildBiomes(world) : null;
    }

    private static int[] buildSurfaceAltitudes(ChunkPrimer primer, boolean overrideSurfaceDetection, int surfaceSearchTopY) {
        int[] surfaceAltitudes = new int[COLUMN_COUNT];
        int subChunkCount = CHUNK_SIZE / BCSettings.SUB_CHUNK_SIZE;

        for (int subX = 0; subX < subChunkCount; subX++) {
            for (int subZ = 0; subZ < subChunkCount; subZ++) {
                int startX = subX * BCSettings.SUB_CHUNK_SIZE;
                int startZ = subZ * BCSettings.SUB_CHUNK_SIZE;
                for (int offsetX = 0; offsetX < BCSettings.SUB_CHUNK_SIZE; offsetX++) {
                    for (int offsetZ = 0; offsetZ < BCSettings.SUB_CHUNK_SIZE; offsetZ++) {
                        int localX = startX + offsetX;
                        int localZ = startZ + offsetZ;
                        surfaceAltitudes[columnIndex(localX, localZ)] = overrideSurfaceDetection
                            ? 1
                            : BetterCavesUtils.searchSurfaceAltitudeInRangeForColumn(primer, localX, localZ, surfaceSearchTopY, 0);
                    }
                }
            }
        }

        return surfaceAltitudes;
    }

    private void buildOceanColumnFields() {
        for (int localX = 0; localX < CHUNK_SIZE; localX++) {
            for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
                int columnIndex = columnIndex(localX, localZ);
                boolean oceanColumn = oceanMask[oceanIndex(localX, localZ)];
                oceanColumns[columnIndex] = oceanColumn;
                oceanSurroundedColumns[columnIndex] =
                    oceanMask[oceanIndex(localX + 1, localZ)]
                        && oceanMask[oceanIndex(localX, localZ - 1)]
                        && oceanMask[oceanIndex(localX - 1, localZ)]
                        && oceanMask[oceanIndex(localX, localZ + 1)];
                if (biomeDistanceFactors != null) {
                    biomeDistanceFactors[columnIndex] =
                        BetterCavesUtils.biomeDistanceFactor(localX, localZ, OCEAN_MASK_BORDER, oceanMask, oceanMaskWidth, !oceanColumn);
                }
            }
        }
    }

    private Biome[] buildBiomes(World world) {
        Biome[] biomes = new Biome[COLUMN_COUNT];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localX = 0; localX < CHUNK_SIZE; localX++) {
            for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
                pos.setPos(getBlockX(localX), 1, getBlockZ(localZ));
                biomes[columnIndex(localX, localZ)] = world.getBiome(pos);
            }
        }
        return biomes;
    }

    private static int columnIndex(int localX, int localZ) {
        return localX * CHUNK_SIZE + localZ;
    }

    private int oceanIndex(int localX, int localZ) {
        return (localX + OCEAN_MASK_BORDER) * oceanMaskWidth + localZ + OCEAN_MASK_BORDER;
    }

    public ChunkPrimer getPrimer() {
        return primer;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public int getBlockX(int localX) {
        return baseBlockX + localX;
    }

    public int getBlockZ(int localZ) {
        return baseBlockZ + localZ;
    }

    public int getSurfaceAltitude(int localX, int localZ) {
        return surfaceAltitudes[columnIndex(localX, localZ)];
    }

    public IBlockState getLiquidBlock(int localX, int localZ) {
        return liquidBlocks[columnIndex(localX, localZ)];
    }

    public IBlockState[] getLiquidBlocksFlat() {
        return liquidBlocks;
    }

    public Biome getBiome(int localX, int localZ) {
        return biomes[columnIndex(localX, localZ)];
    }

    public Biome[] getBiomes() {
        return biomes;
    }

    public boolean[] getOceanMaskFlat() {
        return oceanMask;
    }

    public int getOceanMaskWidth() {
        return oceanMaskWidth;
    }

    public boolean isOceanColumn(int localX, int localZ) {
        return oceanColumns != null && oceanColumns[columnIndex(localX, localZ)];
    }

    public boolean isSurroundedByOcean(int localX, int localZ) {
        return oceanSurroundedColumns != null && oceanSurroundedColumns[columnIndex(localX, localZ)];
    }

    public float getFloodedBoundaryFactor(int localX, int localZ) {
        if (biomeDistanceFactors == null) {
            return 1;
        }
        return biomeDistanceFactors[columnIndex(localX, localZ)];
    }
}
