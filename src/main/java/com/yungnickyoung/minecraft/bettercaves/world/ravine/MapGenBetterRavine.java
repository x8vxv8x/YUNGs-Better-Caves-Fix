package com.yungnickyoung.minecraft.bettercaves.world.ravine;

import com.yungnickyoung.minecraft.bettercaves.config.io.ConfigLoader;
import com.yungnickyoung.minecraft.bettercaves.config.util.ConfigHolder;
import com.yungnickyoung.minecraft.bettercaves.util.BetterCavesUtils;
import com.yungnickyoung.minecraft.bettercaves.world.WaterRegionController;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.gen.MapGenRavine;
import net.minecraftforge.common.BiomeDictionary;
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

    IBlockState[][] currChunkLiquidBlocks;
    boolean[][] currChunkOceanMask;
    int currChunkX, currChunkZ;

    public MapGenBetterRavine(InitMapGenEvent event) {
        this.defaultRavineGen = event.getOriginalGen();
    }

    @Override
    public void generate(World worldIn, int x, int z, @Nonnull ChunkPrimer primer) {
        // Only operate on whitelisted dimensions.
        if (!BetterCavesUtils.isDimensionWhitelisted(worldIn.provider.getDimension())) {
            defaultRavineGen.generate(worldIn, x, z, primer);
            return;
        }

        if (config == null) { // First call - lazy initialization
            this.initialize(worldIn);
        }

        if (config.enableVanillaRavines.get()) {
            super.generate(worldIn, x, z, primer);
        }
    }

    @Override
    protected void digBlock(ChunkPrimer primer, int x, int y, int z, int chunkX, int chunkZ, boolean foundTop) {
        int worldX = x + chunkX * 16;
        int worldZ = z + chunkZ * 16;
        int localX = BetterCavesUtils.getLocal(x);
        int localZ = BetterCavesUtils.getLocal(z);

        if (currChunkLiquidBlocks == null || chunkX != currChunkX || chunkZ != currChunkZ) {
            currChunkLiquidBlocks = waterRegionController.getLiquidBlocksForChunk(chunkX, chunkZ);
            currChunkOceanMask = config.enableFloodedRavines.get()
                ? BetterCavesUtils.getOceanMask(world, chunkX, chunkZ, OCEAN_MASK_BORDER)
                : null;
            currChunkX = chunkX;
            currChunkZ = chunkZ;
        }
        IBlockState liquidBlockState = currChunkLiquidBlocks[localX][localZ];
        if (liquidBlockState == null && y <= config.liquidAltitude.get()) {
            liquidBlockState = LAVA;
        }

        // Don't dig boundaries between flooded and unflooded openings.
        boolean flooded = config.enableFloodedRavines.get()
            && currChunkOceanMask != null
            && currChunkOceanMask[localX + OCEAN_MASK_BORDER][localZ + OCEAN_MASK_BORDER]
            && y < world.getSeaLevel();
        if (flooded) {
            float smoothAmpFactor = BetterCavesUtils.biomeDistanceFactor(localX, localZ, OCEAN_MASK_BORDER, currChunkOceanMask, false);
            if (smoothAmpFactor <= .25f) { // Wall between flooded and normal caves.
                return;
            }
        }

        IBlockState airBlockState = flooded ? WATER : AIR;
        CarverUtils.digBlock(world, primer, worldX, y, worldZ, airBlockState, liquidBlockState, config.liquidAltitude.get(), config.replaceFloatingGravel.get());
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
    }
}
