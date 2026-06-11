package com.yungnickyoung.minecraft.bettercaves.world.ravine;

import com.yungnickyoung.minecraft.bettercaves.config.io.ConfigLoader;
import com.yungnickyoung.minecraft.bettercaves.config.util.ConfigHolder;
import com.yungnickyoung.minecraft.bettercaves.util.BetterCavesUtils;
import com.yungnickyoung.minecraft.bettercaves.world.WaterRegionController;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.gen.MapGenRavine;
import net.minecraftforge.event.terraingen.InitMapGenEvent;

import javax.annotation.Nonnull;

/**
 * Overrides MapGenRavine, tweaking it to work with config options.
 */
public class MapGenBetterRavine extends MapGenRavine {
    private static final IBlockState LAVA = Blocks.LAVA.getDefaultState();
    private static final IBlockState WATER = Blocks.WATER.getDefaultState();
    private ConfigHolder config;
    private WaterRegionController waterRegionController;
    private MapGenBase defaultRavineGen;
    private static final int OCEAN_MASK_BORDER = 2;

    IBlockState[] currChunkLiquidBlocks;
    boolean[] currChunkOceanMask;
    float[] currChunkFloodFactors;
    Biome[] currChunkBiomes;
    int currChunkOceanMaskWidth;
    int currChunkX, currChunkZ;

    public MapGenBetterRavine(InitMapGenEvent event) {
        this.defaultRavineGen = event.getOriginalGen();
    }

    @Override
    public synchronized void generate(World worldIn, int x, int z, @Nonnull ChunkPrimer primer) {
        // Only operate on whitelisted dimensions.
        if (!BetterCavesUtils.isDimensionWhitelisted(worldIn.provider.getDimension())) {
            defaultRavineGen.generate(worldIn, x, z, primer);
            return;
        }

        if (config == null || world != worldIn) { // Lazy initialization
            this.initialize(worldIn);
        }

        if (config.enableVanillaRavines.get()) {
            super.generate(worldIn, x, z, primer);
        }
    }

    @Override
    protected void digBlock(ChunkPrimer primer, int x, int y, int z, int chunkX, int chunkZ, boolean foundTop) {
        int localX = BetterCavesUtils.getLocal(x);
        int localZ = BetterCavesUtils.getLocal(z);

        if (currChunkLiquidBlocks == null || chunkX != currChunkX || chunkZ != currChunkZ) {
            currChunkLiquidBlocks = waterRegionController.getLiquidBlocksFlatForChunk(chunkX, chunkZ);
            currChunkOceanMaskWidth = 16 + OCEAN_MASK_BORDER * 2;
            if (config.enableFloodedRavines.get()) {
                currChunkOceanMask = BetterCavesUtils.getOceanMaskFlat(world, chunkX, chunkZ, OCEAN_MASK_BORDER);
                currChunkFloodFactors = buildFloodFactors(currChunkOceanMask, currChunkOceanMaskWidth);
            }
            else {
                currChunkOceanMask = null;
                currChunkFloodFactors = null;
            }
            currChunkBiomes = buildBiomes(chunkX, chunkZ);
            currChunkX = chunkX;
            currChunkZ = chunkZ;
        }
        int columnIndex = localX * 16 + localZ;
        IBlockState liquidBlockState = currChunkLiquidBlocks[columnIndex];
        if (liquidBlockState == null && y <= config.liquidAltitude.get()) {
            liquidBlockState = LAVA;
        }

        // Don't dig boundaries between flooded and unflooded openings.
        boolean flooded = config.enableFloodedRavines.get()
            && currChunkOceanMask != null
            && currChunkOceanMask[(localX + OCEAN_MASK_BORDER) * currChunkOceanMaskWidth + localZ + OCEAN_MASK_BORDER]
            && y < world.getSeaLevel();
        if (flooded) {
            float smoothAmpFactor = currChunkFloodFactors[columnIndex];
            if (smoothAmpFactor <= .25f) { // Wall between flooded and normal caves.
                return;
            }
        }

        IBlockState airBlockState = flooded ? WATER : AIR;
        CarverUtils.digBlockLocal(primer, localX, y, localZ, currChunkBiomes[columnIndex], airBlockState, liquidBlockState, config.liquidAltitude.get(), config.replaceFloatingGravel.get());
    }

    // Disable built-in water block checks.
    // Without this, ravines in water regions will be sliced up.
    @Override
    protected boolean isOceanBlock(ChunkPrimer data, int x, int y, int z, int chunkX, int chunkZ) {
        return false;
    }

    private void initialize(World worldIn) {
        this.world = worldIn;
        int dimensionID = worldIn.provider.getDimension();
        this.config = ConfigLoader.loadConfigFromFileForDimension(dimensionID);
        this.waterRegionController = new WaterRegionController(world, config);
        this.currChunkLiquidBlocks = null;
        this.currChunkOceanMask = null;
        this.currChunkFloodFactors = null;
        this.currChunkBiomes = null;
    }

    private float[] buildFloodFactors(boolean[] oceanMask, int oceanMaskWidth) {
        float[] factors = new float[16 * 16];
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                factors[localX * 16 + localZ] = BetterCavesUtils.biomeDistanceFactor(localX, localZ, OCEAN_MASK_BORDER, oceanMask, oceanMaskWidth, false);
            }
        }
        return factors;
    }

    private Biome[] buildBiomes(int chunkX, int chunkZ) {
        Biome[] biomes = new Biome[16 * 16];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                pos.setPos(baseX + localX, 1, baseZ + localZ);
                biomes[localX * 16 + localZ] = world.getBiome(pos);
            }
        }
        return biomes;
    }
}
