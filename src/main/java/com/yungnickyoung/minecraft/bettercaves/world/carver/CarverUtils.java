package com.yungnickyoung.minecraft.bettercaves.world.carver;

import com.google.common.collect.ImmutableSet;
import com.yungnickyoung.minecraft.bettercaves.util.BetterCavesUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSand;
import net.minecraft.block.BlockStone;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;

/**
 * Utility functions for Better Caves carvers.
 * This class may not be instantiated - all members are {@code public} and {@code static},
 * and as such may be accessed freely.
 */
public class CarverUtils {
    private CarverUtils() {} // Private constructor prevents instantiation

    /* IBlockStates used in this class */
    private static final IBlockState AIR = Blocks.AIR.getDefaultState();
    private static final IBlockState SAND = Blocks.SAND.getDefaultState();
    private static final IBlockState RED_SAND = Blocks.SAND.getDefaultState().withProperty(BlockSand.VARIANT, BlockSand.EnumType.RED_SAND);
    private static final IBlockState SANDSTONE = Blocks.SANDSTONE.getDefaultState();
    private static final IBlockState REDSANDSTONE = Blocks.RED_SANDSTONE.getDefaultState();
    private static final IBlockState GRAVEL = Blocks.GRAVEL.getDefaultState();
    private static final IBlockState BEDROCK = Blocks.BEDROCK.getDefaultState();
    private static final IBlockState LOG = Blocks.LOG.getDefaultState();
    private static final IBlockState LOG2 = Blocks.LOG2.getDefaultState();
    private static final IBlockState ANDESITE = Blocks.STONE.getDefaultState().withProperty(BlockStone.VARIANT, BlockStone.EnumType.ANDESITE);
    private static final ImmutableSet<IBlockState> DEBUG_BLOCKS = ImmutableSet.of(Blocks.GOLD_BLOCK.getDefaultState(), Blocks.PLANKS.getDefaultState(), Blocks.COBBLESTONE.getDefaultState(), Blocks.REDSTONE_BLOCK.getDefaultState(), Blocks.EMERALD_BLOCK.getDefaultState(), Blocks.BRICK_BLOCK.getDefaultState());

    public static void digBlockLocal(ChunkPrimer primer, int localX, int y, int localZ, Biome biome, IBlockState airBlockState, IBlockState liquidBlockState, int liquidAltitude, boolean replaceGravel) {
        digBlockLocal(primer, localX, y, localZ, biome.topBlock, biome.topBlock.getBlock(), biome.fillerBlock.getBlock(), airBlockState, liquidBlockState, liquidAltitude, replaceGravel);
    }

    public static void digBlockLocal(ChunkPrimer primer, int localX, int y, int localZ, IBlockState biomeTopState, Block biomeTopBlock, Block biomeFillerBlock, IBlockState airBlockState, IBlockState liquidBlockState, int liquidAltitude, boolean replaceGravel) {
        if (y < 0 || y > 255) {
            return;
        }

        IBlockState blockState = primer.getBlockState(localX, y, localZ);
        IBlockState blockStateAbove = y < 255 ? primer.getBlockState(localX, y + 1, localZ) : AIR;

        // Only continue if the block is replaceable
        if (canReplaceBlock(blockState, blockStateAbove) || blockState.getBlock() == biomeTopBlock || blockState.getBlock() == biomeFillerBlock) {
            if (airBlockState == AIR && y <= liquidAltitude) { // Replace any block below the liquid altitude with the liquid block passed in
                if (liquidBlockState != null) {
                    primer.setBlockState(localX, y, localZ, liquidBlockState);
                }
            }
            else {
                // Check for adjacent water blocks to avoid breaking into lakes or oceans
                if (airBlockState == AIR && isWaterAdjacent(primer, localX, y, localZ)) return;

                // Adjust block below if block removed is biome top block
                if (y > 0 && blockState == biomeTopState && canReplaceBlock(primer.getBlockState(localX, y - 1, localZ), AIR))
                    primer.setBlockState(localX, y - 1, localZ, biomeTopState);

                // Replace floating sand with sandstone
                if (y < 255 && blockStateAbove == SAND)
                    primer.setBlockState(localX, y + 1, localZ, SANDSTONE);
                else if (y < 255 && blockStateAbove == RED_SAND)
                    primer.setBlockState(localX, y + 1, localZ, REDSANDSTONE);

                // Replace floating gravel with andesite, if enabled
                if (y < 255 && replaceGravel && blockStateAbove == GRAVEL)
                    primer.setBlockState(localX, y + 1, localZ, ANDESITE);

                // Replace this block with air, effectively "digging" it out
                primer.setBlockState(localX, y, localZ, airBlockState);
            }
        }
    }

    public static void debugDigBlock(ChunkPrimer primer, int x, int y, int z, IBlockState blockState, boolean digBlock) {
        int localX = BetterCavesUtils.getLocal(x);
        int localZ = BetterCavesUtils.getLocal(z);
        debugDigBlockLocal(primer, localX, y, localZ, blockState, digBlock);
    }

    public static void debugDigBlockLocal(ChunkPrimer primer, int localX, int y, int localZ, IBlockState blockState, boolean digBlock) {
        if (y < 0 || y > 255) {
            return;
        }

        if (DEBUG_BLOCKS.contains(primer.getBlockState(localX, y, localZ))) return;

        if (digBlock)
            primer.setBlockState(localX, y, localZ, blockState);
        else
            primer.setBlockState(localX, y, localZ, AIR);
    }

    /**
     * Determines if the Block of a given IBlockState is suitable to be replaced during cave generation.
     * Basically returns true for most common worldgen blocks (e.g. stone, dirt, sand), false if the block is air.
     *
     * @param blockState the block's IBlockState
     * @param blockStateAbove the IBlockState of the block above this one
     * @return true if the blockState can be replaced
     */
    public static boolean canReplaceBlock(IBlockState blockState, IBlockState blockStateAbove) {
        Block block = blockState.getBlock();
        if (block == Blocks.STONE || blockState.getMaterial() == Material.ROCK)
            return blockState != BEDROCK;

        // Avoid damaging trees
        if (block == Blocks.LEAVES
                || block == Blocks.LEAVES2
                || block == Blocks.LOG
                || block == Blocks.LOG2)
            return false;

        // Avoid digging out under trees
        if (blockStateAbove == LOG
                || blockStateAbove == LOG2)
            return false;

        // Don't mine bedrock
        if (blockState == BEDROCK)
            return false;

        // Accept stone-like blocks added from other mods
        // Mine-able blocks
        if (block == Blocks.DIRT
                || block == Blocks.GRASS
                || block == Blocks.HARDENED_CLAY
                || block == Blocks.STAINED_HARDENED_CLAY
                || block == Blocks.SANDSTONE
                || block == Blocks.RED_SANDSTONE
                || block == Blocks.MYCELIUM
                || block  == Blocks.SNOW_LAYER)
            return true;

        // Only accept gravel and sand if water is not directly above it
        return (block == Blocks.SAND || block == Blocks.GRAVEL)
                && blockStateAbove.getMaterial() != Material.WATER;
    }

    private static boolean isWaterAdjacent(ChunkPrimer primer, int localX, int y, int localZ) {
        return y < 255 && primer.getBlockState(localX, y + 1, localZ).getMaterial() == Material.WATER
                || localX < 15 && primer.getBlockState(localX + 1, y, localZ).getMaterial() == Material.WATER
                || localX > 0 && primer.getBlockState(localX - 1, y, localZ).getMaterial() == Material.WATER
                || localZ < 15 && primer.getBlockState(localX, y, localZ + 1).getMaterial() == Material.WATER
                || localZ > 0 && primer.getBlockState(localX, y, localZ - 1).getMaterial() == Material.WATER;
    }
}
