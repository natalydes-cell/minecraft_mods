package com.realearth.worldgen;

import com.realearth.climate.KoppenClass;
import com.realearth.data.EarthData;
import com.realearth.util.GeoProjection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Replaces the top few blocks of raw stone with the material the real climate and slope produce.
 *
 * <p>Soil is not decoration - it is the output of a climate acting on rock for thousands of years,
 * and it is what makes a place readable at a glance. Desert gets sand, taiga gets podzol, a steep
 * mountainside gets bare stone because soil cannot stay on it, and a tropical floodplain gets deep
 * rich dirt. The same rule then feeds crop fertility, so farmland is good exactly where the ground
 * looks like it should be.
 */
public final class SurfaceDresser {

    private SurfaceDresser() {}

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState();
    private static final BlockState PODZOL = Blocks.PODZOL.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState RED_SAND = Blocks.RED_SAND.defaultBlockState();
    private static final BlockState SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState PACKED_ICE = Blocks.PACKED_ICE.defaultBlockState();
    private static final BlockState TERRACOTTA = Blocks.TERRACOTTA.defaultBlockState();

    public static void dress(ChunkAccess chunk, EarthData data, GeoProjection proj) {
        int originX = chunk.getPos().getMinBlockX();
        int originZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int worldX = originX + lx;
                int worldZ = originZ + lz;
                double lat = proj.latitude(worldZ);
                double lon = proj.longitude(worldX);

                double metres = data.elevation(lat, lon);
                if (metres < -40) continue;   // deep seafloor: leave it stone

                int top = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, lx, lz);
                KoppenClass k = data.koppen(lat, lon);
                double slope = data.slopeDegrees(lat, lon);

                BlockState surface = surfaceFor(k, metres, lat, slope, top);
                BlockState subsoil = subsoilFor(k, surface);
                int depth = soilDepth(k, slope);

                chunk.setBlockState(cursor.set(lx, top, lz), surface, false);
                for (int d = 1; d <= depth; d++) {
                    int y = top - d;
                    if (y < chunk.getMinBuildHeight()) break;
                    chunk.setBlockState(cursor.set(lx, y, lz), subsoil, false);
                }
            }
        }
    }

    private static BlockState surfaceFor(KoppenClass k, double metres, double lat, double slope,
                                         int topY) {
        // Nothing holds on a cliff. This is why the north face of the Eiger is rock and the
        // meadow below it is grass, in the same climate.
        if (slope > 33) return STONE;
        if (slope > 24) return GRAVEL;

        if (k == KoppenClass.EF_ICE_CAP) return metres > 1200 ? PACKED_ICE : SNOW_BLOCK;

        // Permanent snow above the local snowline, which drops towards the poles exactly as the
        // treeline does.
        double snowline = 4900.0 - Math.abs(lat) * 66.0;
        if (metres > snowline) return SNOW_BLOCK;

        return switch (k) {
            case BWH_DESERT_HOT -> SAND;
            case BWK_DESERT_COLD -> RED_SAND;
            case BSH_STEPPE_HOT -> COARSE_DIRT;
            case BSK_STEPPE_COLD -> COARSE_DIRT;
            case DFC_SUBARCTIC, DFD_SUBARCTIC_SV, DWC_CONT_DRYWIN, DWD_CONT_DRYWIN -> PODZOL;
            case ET_TUNDRA -> COARSE_DIRT;
            case OCEAN -> GRAVEL;
            default -> GRASS;
        };
    }

    private static BlockState subsoilFor(KoppenClass k, BlockState surface) {
        if (surface == SAND) return SANDSTONE;
        if (surface == RED_SAND) return TERRACOTTA;
        if (surface == SNOW_BLOCK || surface == PACKED_ICE) return PACKED_ICE;
        if (surface == STONE || surface == GRAVEL) return STONE;
        return DIRT;
    }

    /**
     * How deep the soil runs. Wet warm climates weather rock fastest and build the deepest
     * profiles; deserts and steep ground have almost none.
     */
    private static int soilDepth(KoppenClass k, double slope) {
        int base = k.isTropical() ? 6 : (k.isDesert() ? 2 : (k.isPolar() ? 1 : 4));
        if (slope > 18) base = Math.max(1, base - 2);
        return base;
    }
}
