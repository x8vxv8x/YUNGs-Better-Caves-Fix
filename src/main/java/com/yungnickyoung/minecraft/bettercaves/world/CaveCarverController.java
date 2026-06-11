package com.yungnickyoung.minecraft.bettercaves.world;

import com.yungnickyoung.minecraft.bettercaves.BetterCaves;
import com.yungnickyoung.minecraft.bettercaves.config.util.ConfigHolder;
import com.yungnickyoung.minecraft.bettercaves.config.BCSettings;
import com.yungnickyoung.minecraft.bettercaves.enums.CaveType;
import com.yungnickyoung.minecraft.bettercaves.enums.RegionSize;
import com.yungnickyoung.minecraft.bettercaves.noise.FastNoise;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverNoiseRange;
import com.yungnickyoung.minecraft.bettercaves.world.carver.ICarver;
import com.yungnickyoung.minecraft.bettercaves.world.carver.cave.CaveCarver;
import com.yungnickyoung.minecraft.bettercaves.world.carver.cave.CaveCarverBuilder;
import com.yungnickyoung.minecraft.bettercaves.world.carver.vanilla.VanillaCaveCarver;
import com.yungnickyoung.minecraft.bettercaves.world.carver.vanilla.VanillaCaveCarverBuilder;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;

import java.util.ArrayList;
import java.util.List;

public class CaveCarverController {
    private World world;
    private VanillaCaveCarver surfaceCaveCarver; // only used if surface caves enabled
    private FastNoise caveRegionController;
    private List<CarverNoiseRange> noiseRanges = new ArrayList<>();

    // Vars from config
    private boolean isDebugViewEnabled;
    private boolean isOverrideSurfaceDetectionEnabled;
    private boolean isSurfaceCavesEnabled;
    private boolean isFloodedUndergroundEnabled;
    private boolean hasNonVanillaCaves;

    public CaveCarverController(World worldIn, ConfigHolder config) {
        this.world = worldIn;
        this.isDebugViewEnabled = config.debugVisualizer.get();
        this.isOverrideSurfaceDetectionEnabled = config.overrideSurfaceDetection.get();
        this.isSurfaceCavesEnabled = config.isSurfaceCavesEnabled.get();
        this.isFloodedUndergroundEnabled = config.enableFloodedUnderground.get();
        this.surfaceCaveCarver = new VanillaCaveCarverBuilder()
            .bottomY(config.surfaceCaveBottom.get())
            .topY(config.surfaceCaveTop.get())
            .density(config.surfaceCaveDensity.get())
            .liquidAltitude(config.liquidAltitude.get())
            .replaceGravel(config.replaceFloatingGravel.get())
            .floodedUnderground(config.enableFloodedUnderground.get())
            .debugVisualizerEnabled(config.debugVisualizer.get())
            .debugVisualizerBlock(Blocks.EMERALD_BLOCK.getDefaultState())
            .build();

        // Configure cave region controller, which determines what type of cave should be
        // carved in any given region
        float caveRegionSize = calcCaveRegionSize(config.caveRegionSize.get(), config.caveRegionCustomSize.get());
        this.caveRegionController = new FastNoise();
        this.caveRegionController.SetSeed((int)worldIn.getSeed() + 222);
        this.caveRegionController.SetFrequency(caveRegionSize);
        this.caveRegionController.SetNoiseType(FastNoise.NoiseType.Cellular);
        this.caveRegionController.SetCellularDistanceFunction(FastNoise.CellularDistanceFunction.Natural);

        // Initialize all carvers using config options
        List<ICarver> carvers = new ArrayList<>();
        // Type 1 caves
        carvers.add(new CaveCarverBuilder(worldIn)
            .ofTypeFromConfig(CaveType.CUBIC, config)
            .debugVisualizerBlock(Blocks.PLANKS.getDefaultState())
            .build()
        );
        // Type 2 caves
        carvers.add(new CaveCarverBuilder(worldIn)
            .ofTypeFromConfig(CaveType.SIMPLEX, config)
            .debugVisualizerBlock(Blocks.COBBLESTONE.getDefaultState())
            .build()
        );
        // Vanilla caves
        carvers.add(new VanillaCaveCarverBuilder()
            .bottomY(config.vanillaCaveBottom.get())
            .topY(config.vanillaCaveTop.get())
            .density(config.vanillaCaveDensity.get())
            .priority(config.vanillaCavePriority.get())
            .liquidAltitude(config.liquidAltitude.get())
            .replaceGravel(config.replaceFloatingGravel.get())
            .floodedUnderground(config.enableFloodedUnderground.get())
            .debugVisualizerEnabled(config.debugVisualizer.get())
            .debugVisualizerBlock(Blocks.BRICK_BLOCK.getDefaultState())
            .build());

        // Remove carvers with no priority
        carvers.removeIf(carver -> carver.getPriority() == 0);
        this.hasNonVanillaCaves = carvers.stream().anyMatch(carver -> carver instanceof CaveCarver);

        // Initialize vars for calculating controller noise thresholds
        float maxPossibleNoiseThreshold = config.caveSpawnChance.get() * .01f * 2 - 1;
        int totalPriority = carvers.stream().map(ICarver::getPriority).reduce(0, Integer::sum);
        float totalRangeLength = maxPossibleNoiseThreshold - -1f;
        float currNoise = -1f;

        BetterCaves.LOGGER.debug("CAVE INFORMATION");
        BetterCaves.LOGGER.debug("--> MAX POSSIBLE THRESHOLD: " + maxPossibleNoiseThreshold);
        BetterCaves.LOGGER.debug("--> TOTAL PRIORITY: " + totalPriority);
        BetterCaves.LOGGER.debug("--> TOTAL RANGE LENGTH: " + totalRangeLength);

        for (ICarver carver : carvers) {
            BetterCaves.LOGGER.debug("--> CARVER");
            float noiseRangeLength = (float)carver.getPriority() / totalPriority * totalRangeLength;
            float rangeTop = currNoise + noiseRangeLength;
            CarverNoiseRange range = new CarverNoiseRange(currNoise, rangeTop, carver);
            currNoise = rangeTop;
            noiseRanges.add(range);

            BetterCaves.LOGGER.debug("    --> RANGE FOUND: " + range);
        }
    }

    public void carveChunk(ChunkPrimer primer, int chunkX, int chunkZ, int[][] surfaceAltitudes, IBlockState[][] liquidBlocks, boolean[][] oceanMask) {
        // Prevent unnecessary computation if caves are disabled
        if (noiseRanges.size() == 0 && !isSurfaceCavesEnabled) {
            return;
        }

        boolean flooded;

        // Flag to keep track of whether or not we've already carved vanilla caves for this chunk, since
        // vanilla caves operate on a chunk-by-chunk basis rather than by column
        boolean shouldCarveVanillaCaves = false;

        // Since vanilla caves carve by chunk and not by column, we store an array
        // indicating which x-z coordinates are valid to be carved in
        boolean[][] vanillaCarvingMask = new boolean[16][16];

        BlockPos.MutableBlockPos colPos = new BlockPos.MutableBlockPos();
        int subChunkCount = 16 / BCSettings.SUB_CHUNK_SIZE;
        CarverNoiseRange[] activeRanges = new CarverNoiseRange[BCSettings.SUB_CHUNK_SIZE * BCSettings.SUB_CHUNK_SIZE];
        int[] activeTopYs = new int[BCSettings.SUB_CHUNK_SIZE * BCSettings.SUB_CHUNK_SIZE];

        // Break into subchunks for noise interpolation
        for (int subX = 0; subX < subChunkCount; subX++) {
            for (int subZ = 0; subZ < subChunkCount; subZ++) {
                int startX = subX * BCSettings.SUB_CHUNK_SIZE;
                int startZ = subZ * BCSettings.SUB_CHUNK_SIZE;
                int endX = startX + BCSettings.SUB_CHUNK_SIZE - 1;
                int endZ = startZ + BCSettings.SUB_CHUNK_SIZE - 1;

                noiseRanges.forEach(range -> range.setNoiseCube(null));
                int activeColumnCount = 0;

                for (int offsetX = 0; offsetX < BCSettings.SUB_CHUNK_SIZE; offsetX++) {
                    for (int offsetZ = 0; offsetZ < BCSettings.SUB_CHUNK_SIZE; offsetZ++) {
                        int columnIndex = offsetX * BCSettings.SUB_CHUNK_SIZE + offsetZ;
                        int localX = startX + offsetX;
                        int localZ = startZ + offsetZ;
                        colPos.setPos(chunkX * 16 + localX, 1, chunkZ * 16 + localZ);
                        activeRanges[columnIndex] = null;
                        activeTopYs[columnIndex] = 0;
                        flooded = isFloodedUndergroundEnabled && !isDebugViewEnabled && oceanMask != null && oceanMask[localX + 2][localZ + 2];
                        if (flooded) {
                            if (
                                !oceanMask[localX + 3][localZ + 2] ||
                                !oceanMask[localX + 2][localZ + 1] ||
                                !oceanMask[localX + 1][localZ + 2] ||
                                !oceanMask[localX + 2][localZ + 3]
                            ) {
                                continue;
                            }
                        }

                        int surfaceAltitude = surfaceAltitudes[localX][localZ];
                        IBlockState liquidBlock = liquidBlocks[localX][localZ];

                        // Get noise values used to determine cave region
                        float caveRegionNoise = caveRegionController.GetNoise(colPos.getX(), colPos.getZ());

                        // Carve cave using matching carver
                        for (CarverNoiseRange range : noiseRanges) {
                            if (!range.contains(caveRegionNoise)) {
                                continue;
                            }
                            if (range.getCarver() instanceof CaveCarver) {
                                CaveCarver carver = (CaveCarver) range.getCarver();
                                int topY = Math.min(surfaceAltitude, carver.getTopY());
                                if (isOverrideSurfaceDetectionEnabled) {
                                    topY = carver.getTopY();
                                }
                                if (isDebugViewEnabled) {
                                    topY = 128;
                                }
                                activeRanges[columnIndex] = range;
                                activeTopYs[columnIndex] = topY;
                                activeColumnCount++;
                                break;
                            }
                            else if (range.getCarver() instanceof VanillaCaveCarver) {
                                vanillaCarvingMask[localX][localZ] = true;
                                shouldCarveVanillaCaves = true;
                                break;
                            }
                        }
                    }
                }

                for (CarverNoiseRange range : noiseRanges) {
                    if (!(range.getCarver() instanceof CaveCarver)) {
                        continue;
                    }

                    CaveCarver carver = (CaveCarver) range.getCarver();
                    int neededMaxHeight = Integer.MIN_VALUE;
                    for (int i = 0; i < activeRanges.length; i++) {
                        if (activeRanges[i] == range) {
                            neededMaxHeight = Math.max(neededMaxHeight, activeTopYs[i]);
                        }
                    }
                    if (neededMaxHeight == Integer.MIN_VALUE) {
                        continue;
                    }

                    range.setNoiseCube(carver.getNoiseGen().interpolateNoiseCube(
                        chunkX * 16 + startX,
                        chunkX * 16 + endX,
                        chunkZ * 16 + startZ,
                        chunkZ * 16 + endZ,
                        carver.getBottomY(),
                        neededMaxHeight
                    ));
                }

                if (activeColumnCount == 0) {
                    continue;
                }

                for (int offsetX = 0; offsetX < BCSettings.SUB_CHUNK_SIZE; offsetX++) {
                    for (int offsetZ = 0; offsetZ < BCSettings.SUB_CHUNK_SIZE; offsetZ++) {
                        int columnIndex = offsetX * BCSettings.SUB_CHUNK_SIZE + offsetZ;
                        CarverNoiseRange activeRange = activeRanges[columnIndex];
                        if (activeRange == null || !(activeRange.getCarver() instanceof CaveCarver)) {
                            continue;
                        }

                        int localX = startX + offsetX;
                        int localZ = startZ + offsetZ;
                        colPos.setPos(chunkX * 16 + localX, 1, chunkZ * 16 + localZ);
                        flooded = isFloodedUndergroundEnabled && !isDebugViewEnabled && oceanMask != null && oceanMask[localX + 2][localZ + 2];
                        CaveCarver carver = (CaveCarver) activeRange.getCarver();
                        carver.carveColumn(primer, colPos, activeTopYs[columnIndex], activeRange.getNoiseCube(), offsetX, offsetZ, liquidBlocks[localX][localZ], flooded);
                    }
                }
            }
        }
        if (shouldCarveVanillaCaves) {
            VanillaCaveCarver carver = null;
            for (CarverNoiseRange range : noiseRanges) {
                if (range.getCarver() instanceof VanillaCaveCarver) {
                    carver = (VanillaCaveCarver) range.getCarver();
                    break;
                }
            }
            if (carver != null) {
                carver.generate(world, chunkX, chunkZ, primer, true, liquidBlocks, vanillaCarvingMask, oceanMask);
            }
        }
        // Generate surface caves if enabled
        if (isSurfaceCavesEnabled) {
            boolean[][] surfaceCarvingMask = new boolean[16][16];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    surfaceCarvingMask[x][z] = true;
                }
            }
            surfaceCaveCarver.generate(world, chunkX, chunkZ, primer, false, liquidBlocks, surfaceCarvingMask, oceanMask);
        }
    }

    /**
     * @return frequency value for cave region controller
     */
    private float calcCaveRegionSize(RegionSize caveRegionSize, float caveRegionCustomSize) {
        switch (caveRegionSize) {
            case Small:
                return .008f;
            case Large:
                return .0032f;
            case ExtraLarge:
                return .001f;
            case Custom:
                return caveRegionCustomSize;
            default: // Medium
                return .005f;
        }
    }

    public boolean hasWork() {
        return !noiseRanges.isEmpty() || isSurfaceCavesEnabled;
    }

    public boolean needsSurfaceAltitudes() {
        return hasNonVanillaCaves;
    }

    public boolean needsOceanMask() {
        return isFloodedUndergroundEnabled && hasWork();
    }
}
