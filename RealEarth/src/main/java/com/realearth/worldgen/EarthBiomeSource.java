package com.realearth.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.realearth.climate.KoppenClass;
import com.realearth.core.RealEarth;
import com.realearth.data.EarthData;
import com.realearth.util.GeoProjection;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

/**
 * Chooses biomes from the real climate and elevation instead of from noise.
 *
 * <p>The Koppen class decides the broad type, then elevation and depth refine it: the same
 * subarctic class is taiga on a plain and bare stone above the treeline, and the same ocean class
 * is a warm shallow reef at 30 m and a cold deep ocean at 3000 m. This is why the Sahara stops
 * exactly where the Sahel begins rather than fading through a noise gradient.
 *
 * <p>Only vanilla biomes are used. That is a compatibility decision: a mod adding its own biome
 * registry entries forces every other mod's biome-keyed features, spawns and Cold Sweat
 * temperature overrides to be updated too, and almost none of them would be.
 */
public class EarthBiomeSource extends BiomeSource {

    public static final MapCodec<EarthBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i
            .group(RegistryOps.retrieveGetter(Registries.BIOME))
            .apply(i, EarthBiomeSource::new));

    private final HolderGetter<Biome> biomes;

    public EarthBiomeSource(HolderGetter<Biome> biomes) {
        this.biomes = biomes;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(
                Biomes.OCEAN, Biomes.DEEP_OCEAN, Biomes.WARM_OCEAN, Biomes.LUKEWARM_OCEAN,
                Biomes.COLD_OCEAN, Biomes.FROZEN_OCEAN, Biomes.DEEP_FROZEN_OCEAN,
                Biomes.DEEP_COLD_OCEAN, Biomes.DEEP_LUKEWARM_OCEAN,
                Biomes.BEACH, Biomes.SNOWY_BEACH, Biomes.STONY_SHORE,
                Biomes.DESERT, Biomes.BADLANDS, Biomes.SAVANNA, Biomes.SAVANNA_PLATEAU,
                Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE,
                Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.FOREST, Biomes.FLOWER_FOREST,
                Biomes.BIRCH_FOREST, Biomes.DARK_FOREST, Biomes.OLD_GROWTH_BIRCH_FOREST,
                Biomes.TAIGA, Biomes.SNOWY_TAIGA, Biomes.OLD_GROWTH_PINE_TAIGA,
                Biomes.OLD_GROWTH_SPRUCE_TAIGA,
                Biomes.SWAMP, Biomes.MANGROVE_SWAMP,
                Biomes.WINDSWEPT_HILLS, Biomes.WINDSWEPT_FOREST, Biomes.WINDSWEPT_GRAVELLY_HILLS,
                Biomes.MEADOW, Biomes.GROVE, Biomes.SNOWY_SLOPES, Biomes.JAGGED_PEAKS,
                Biomes.FROZEN_PEAKS, Biomes.STONY_PEAKS, Biomes.CHERRY_GROVE,
                Biomes.SNOWY_PLAINS, Biomes.ICE_SPIKES, Biomes.FROZEN_RIVER, Biomes.RIVER
        ).map(biomes::getOrThrow);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        // Biome coordinates are quarter-resolution, so scale back to blocks first.
        int blockX = quartX << 2;
        int blockZ = quartZ << 2;

        GeoProjection proj = RealEarth.projection();
        EarthData data = RealEarth.data();

        double lat = proj.latitude(blockZ);
        double lon = proj.longitude(blockX);
        double metres = data.elevation(lat, lon);
        KoppenClass k = data.koppen(lat, lon);

        return biomes.getOrThrow(pick(k, metres, lat));
    }

    private static ResourceKey<Biome> pick(KoppenClass k, double metres, double lat) {
        if (metres < 0) return ocean(k, metres, lat);
        if (metres < 4) return k.isPolar() ? Biomes.SNOWY_BEACH : Biomes.BEACH;

        // Above the treeline nothing grows, and the treeline itself drops towards the poles:
        // roughly 4000 m at the equator, sea level at the Arctic circle. This single rule is what
        // puts bare rock and snow on top of Nepal, the Alps and the Caucasus without naming any
        // of them.
        double treeline = 4300.0 - Math.abs(lat) * 62.0;
        if (metres > treeline + 900) return Biomes.FROZEN_PEAKS;
        if (metres > treeline + 400) return Biomes.JAGGED_PEAKS;
        if (metres > treeline)       return k.annualRainMm() < 400 ? Biomes.STONY_PEAKS
                                                                  : Biomes.SNOWY_SLOPES;
        if (metres > treeline - 500) return k.isPolar() ? Biomes.SNOWY_SLOPES : Biomes.GROVE;
        if (metres > treeline - 1100) return Biomes.MEADOW;

        return switch (k) {
            case AF_TROPICAL_RAIN -> Biomes.JUNGLE;
            case AM_TROPICAL_MONS -> Biomes.BAMBOO_JUNGLE;
            case AW_TROPICAL_SAV  -> Biomes.SAVANNA;

            case BWH_DESERT_HOT   -> Biomes.DESERT;
            case BWK_DESERT_COLD  -> Biomes.BADLANDS;
            case BSH_STEPPE_HOT   -> Biomes.SAVANNA_PLATEAU;
            case BSK_STEPPE_COLD  -> Biomes.PLAINS;

            case CSA_MED_HOT, CSB_MED_WARM -> Biomes.SPARSE_JUNGLE;
            case CSC_MED_COLD     -> Biomes.WINDSWEPT_FOREST;
            case CWA_HUMID_SUBTR, CFA_HUMID_SUBTR -> Biomes.FOREST;
            case CWB_SUBTR_HIGH   -> Biomes.CHERRY_GROVE;
            case CWC_SUBTR_COLD   -> Biomes.WINDSWEPT_HILLS;
            case CFB_OCEANIC      -> Biomes.FLOWER_FOREST;
            case CFC_SUBPOLAR_OC  -> Biomes.WINDSWEPT_FOREST;

            case DFA_CONT_HUMID   -> Biomes.DARK_FOREST;
            case DFB_CONT_HUMID   -> Biomes.BIRCH_FOREST;
            case DFC_SUBARCTIC    -> Biomes.TAIGA;
            case DFD_SUBARCTIC_SV -> Biomes.SNOWY_TAIGA;
            case DSA_CONT_DRYSUM, DSB_CONT_DRYSUM -> Biomes.PLAINS;
            case DSC_CONT_DRYSUM, DSD_CONT_DRYSUM -> Biomes.SNOWY_TAIGA;
            case DWA_CONT_DRYWIN, DWB_CONT_DRYWIN -> Biomes.OLD_GROWTH_BIRCH_FOREST;
            case DWC_CONT_DRYWIN  -> Biomes.OLD_GROWTH_PINE_TAIGA;
            case DWD_CONT_DRYWIN  -> Biomes.OLD_GROWTH_SPRUCE_TAIGA;

            case ET_TUNDRA        -> Biomes.SNOWY_PLAINS;
            case EF_ICE_CAP       -> Biomes.ICE_SPIKES;

            case OCEAN            -> Biomes.PLAINS; // land pixel misread as ocean; harmless
        };
    }

    /**
     * Ocean biome from depth and temperature. Depth matters as much as latitude: a tropical
     * lagoon and the Puerto Rico Trench sit at the same latitude and are nothing alike.
     */
    private static ResourceKey<Biome> ocean(KoppenClass k, double metres, double lat) {
        boolean deep = metres < -1400;
        double absLat = Math.abs(lat);

        if (absLat > 66) return deep ? Biomes.DEEP_FROZEN_OCEAN : Biomes.FROZEN_OCEAN;
        if (absLat > 50) return deep ? Biomes.DEEP_COLD_OCEAN : Biomes.COLD_OCEAN;
        if (absLat > 30) return deep ? Biomes.DEEP_OCEAN : Biomes.OCEAN;
        if (absLat > 18) return deep ? Biomes.DEEP_LUKEWARM_OCEAN : Biomes.LUKEWARM_OCEAN;
        return deep ? Biomes.DEEP_LUKEWARM_OCEAN : Biomes.WARM_OCEAN;
    }
}
