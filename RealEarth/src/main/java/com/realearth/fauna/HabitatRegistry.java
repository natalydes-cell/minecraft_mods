package com.realearth.fauna;

import com.realearth.climate.KoppenClass;

import java.util.ArrayList;
import java.util.List;

import static com.realearth.climate.KoppenClass.*;
import static com.realearth.fauna.Habitat.climates;

/**
 * Real ranges for the vanilla animals.
 *
 * <p>Only vanilla entities, for the same reason the biome source uses only vanilla biomes: adding
 * creatures is a different mod's job, and a modpack that already has a wildlife mod should be able
 * to keep it. What this table changes is <em>where</em> the existing animals are allowed to be.
 *
 * <p>The ranges are the real ones. Polar bears are Arctic and coastal - they hunt seals from sea
 * ice, so a landlocked polar bear is not a polar bear. Pandas are the Sichuan bamboo forests and
 * nowhere else. Parrots are South American and African tropics but not Australian. Llamas are the
 * Andes above 2500 m. Camels are the Sahara and Arabia. Wolves get the whole northern hemisphere
 * because they genuinely had it.
 */
public final class HabitatRegistry {

    private HabitatRegistry() {}

    public static List<Habitat> builtin() {
        List<Habitat> h = new ArrayList<>();

        // --- Arctic ---------------------------------------------------------------------
        // Coastal only: polar bears live on sea ice, not on tundra plateaus.
        h.add(new Habitat("minecraft:polar_bear", 66, 90, -180, 180,
                climates(ET_TUNDRA, EF_ICE_CAP, DFD_SUBARCTIC_SV), 0, 900, true, 8));
        h.add(new Habitat("minecraft:rabbit", 55, 82, -180, 180,
                climates(ET_TUNDRA, DFC_SUBARCTIC, DFD_SUBARCTIC_SV), 0, 1500, false, 6));

        // --- Boreal and temperate northern hemisphere -----------------------------------
        h.add(Habitat.global("minecraft:wolf", 30, 78,
                climates(DFB_CONT_HUMID, DFC_SUBARCTIC, DFD_SUBARCTIC_SV, DFA_CONT_HUMID,
                        DWC_CONT_DRYWIN, DWD_CONT_DRYWIN, ET_TUNDRA), 0, 3500, 10));
        h.add(Habitat.global("minecraft:fox", 25, 80,
                climates(DFB_CONT_HUMID, DFC_SUBARCTIC, CFB_OCEANIC, ET_TUNDRA,
                        DFA_CONT_HUMID, CSB_MED_WARM), 0, 2500, 9));
        h.add(new Habitat("minecraft:goat", 25, 60, -130, 100,
                climates(), 1400, 5200, false, 10));
        h.add(new Habitat("minecraft:horse", 35, 56, 20, 120,
                climates(BSK_STEPPE_COLD, DFB_CONT_HUMID, DSB_CONT_DRYSUM), 0, 2200, false, 8));

        // --- Bamboo forests of Sichuan --------------------------------------------------
        // The whole wild population lives in six mountain ranges in one province. This is the
        // narrowest range in the table on purpose - finding one should mean something.
        h.add(new Habitat("minecraft:panda", 28, 34, 102, 109,
                climates(CWB_SUBTR_HIGH, CFA_HUMID_SUBTR, CWA_HUMID_SUBTR), 1200, 3400, false, 4));

        // --- Tropical -------------------------------------------------------------------
        // South American and African tropics. Deliberately not Australia or Southeast Asia,
        // where vanilla would happily put them.
        h.add(new Habitat("minecraft:parrot", -25, 22, -95, 45,
                climates(AF_TROPICAL_RAIN, AM_TROPICAL_MONS, AW_TROPICAL_SAV), 0, 2000, false, 9));
        h.add(new Habitat("minecraft:ocelot", -30, 23, -110, -35,
                climates(AF_TROPICAL_RAIN, AM_TROPICAL_MONS, AW_TROPICAL_SAV), 0, 1800, false, 7));
        h.add(new Habitat("minecraft:frog", -35, 55, -180, 180,
                climates(AF_TROPICAL_RAIN, AM_TROPICAL_MONS, CFA_HUMID_SUBTR, CWA_HUMID_SUBTR),
                0, 1200, false, 8));

        // --- Andes ----------------------------------------------------------------------
        h.add(new Habitat("minecraft:llama", -35, 10, -80, -62,
                climates(), 2500, 5000, false, 10));

        // --- Deserts --------------------------------------------------------------------
        h.add(new Habitat("minecraft:camel", 12, 42, -17, 78,
                climates(BWH_DESERT_HOT, BWK_DESERT_COLD, BSH_STEPPE_HOT), 0, 1800, false, 9));
        h.add(new Habitat("minecraft:armadillo", -40, 35, -120, -35,
                climates(BSH_STEPPE_HOT, AW_TROPICAL_SAV, BWH_DESERT_HOT), 0, 2000, false, 6));

        // --- Australia ------------------------------------------------------------------
        // Sniffers stand in for the continent's oddities; nothing else vanilla fits.
        h.add(new Habitat("minecraft:sniffer", -44, -10, 112, 154,
                climates(), 0, 1500, false, 3));

        // --- Cosmopolitan farm animals --------------------------------------------------
        // Broad but not unlimited: none of these survive an ice cap or a true desert interior.
        h.add(Habitat.global("minecraft:cow", -50, 68,
                climates(CFB_OCEANIC, CFA_HUMID_SUBTR, DFB_CONT_HUMID, DFA_CONT_HUMID,
                        CSB_MED_WARM, CSA_MED_HOT, AW_TROPICAL_SAV, BSK_STEPPE_COLD), 0, 3000, 12));
        h.add(Habitat.global("minecraft:sheep", -50, 70,
                climates(CFB_OCEANIC, CSB_MED_WARM, DFB_CONT_HUMID, DFC_SUBARCTIC,
                        BSK_STEPPE_COLD, ET_TUNDRA), 0, 4200, 12));
        h.add(Habitat.global("minecraft:pig", -45, 62,
                climates(CFB_OCEANIC, CFA_HUMID_SUBTR, DFB_CONT_HUMID, DFA_CONT_HUMID,
                        AF_TROPICAL_RAIN, AM_TROPICAL_MONS), 0, 2200, 11));
        h.add(Habitat.global("minecraft:chicken", -45, 62,
                climates(), 0, 2500, 12));
        h.add(Habitat.global("minecraft:donkey", 15, 48,
                climates(BSH_STEPPE_HOT, BSK_STEPPE_COLD, CSA_MED_HOT, BWH_DESERT_HOT), 0, 2800, 6));
        h.add(new Habitat("minecraft:mooshroom", 63, 67, -25, -13,
                climates(), 0, 900, false, 2));   // Iceland, and only Iceland

        // --- Marine ---------------------------------------------------------------------
        h.add(new Habitat("minecraft:dolphin", -45, 60, -180, 180,
                climates(OCEAN), -3000, 0, false, 10));
        h.add(new Habitat("minecraft:cod", 35, 78, -80, 60,
                climates(OCEAN), -600, 0, false, 12));      // North Atlantic and Arctic
        h.add(new Habitat("minecraft:salmon", 40, 72, -180, 180,
                climates(OCEAN), -400, 0, false, 10));
        h.add(new Habitat("minecraft:tropical_fish", -30, 30, -180, 180,
                climates(OCEAN), -120, 0, false, 14));      // reefs: shallow and warm
        h.add(new Habitat("minecraft:pufferfish", -30, 35, -200, 0,
                climates(OCEAN), -150, 0, false, 8));
        h.add(new Habitat("minecraft:turtle", -35, 35, -180, 180,
                climates(OCEAN), -40, 5, true, 6));
        h.add(new Habitat("minecraft:squid", -60, 70, -180, 180,
                climates(OCEAN), -2000, 0, false, 12));
        h.add(new Habitat("minecraft:glow_squid", -60, 70, -180, 180,
                climates(OCEAN), -4000, -600, false, 8));   // deep water only
        h.add(new Habitat("minecraft:axolotl", 19, 20, -100, -98,
                climates(), 2100, 2300, false, 2));         // Lake Xochimilco, Mexico City

        return List.copyOf(h);
    }

    /** Every habitat that permits this position, for the spawner to weight and pick from. */
    public static List<Habitat> at(List<Habitat> all, double lat, double lon, double elevM,
                                   KoppenClass climate, boolean nearCoast) {
        List<Habitat> hits = new ArrayList<>(4);
        for (Habitat hab : all) {
            if (hab.allows(lat, lon, elevM, climate, nearCoast)) hits.add(hab);
        }
        return hits;
    }
}
