package com.realearth.worldgen;

import com.realearth.worldgen.Deposit.Resource;

import java.util.ArrayList;
import java.util.List;

/**
 * The built-in table of real mineral provinces, plus whatever the operator adds in
 * {@code config/realearth/deposits.json}.
 *
 * <p>Coordinates and depth bands come from public geological surveys. They are approximations of
 * real basins, not survey data: the intent is that a player who knows where Kuzbass is finds coal
 * there, and that someone prospecting with Create's drills has a reason to travel.
 *
 * <p>Depths are the real ones. Kuznetsk seams run from near-surface strip mines down past 500 m,
 * which is why the band is wide; North Sea oil sits under a kilometre of rock and needs real
 * drilling. Those depths then pass through {@link ElevationCurve}, so a deposit stays at the right
 * geological level whatever the vertical compression is doing.
 */
public final class DepositRegistry {

    private DepositRegistry() {}

    public static List<Deposit> builtin() {
        List<Deposit> d = new ArrayList<>();

        // --- Coal -------------------------------------------------------------------------
        d.add(new Deposit("kuzbass", "Kuznetsk Basin", Resource.COAL,
                54.0, 86.5, 2.6, 3.4, 20, 700, 5.0));
        d.add(new Deposit("donbass", "Donets Basin", Resource.COAL,
                48.3, 38.5, 1.9, 3.0, 60, 1200, 4.0));
        d.add(new Deposit("pechora", "Pechora Basin", Resource.COAL,
                67.5, 63.0, 2.5, 5.0, 100, 900, 2.6));
        d.add(new Deposit("tunguska", "Tunguska Basin", Resource.COAL,
                64.0, 100.0, 7.0, 12.0, 50, 600, 2.2));
        d.add(new Deposit("appalachian", "Appalachian Coalfield", Resource.COAL,
                38.5, -81.0, 3.5, 3.0, 20, 500, 4.2));
        d.add(new Deposit("powder_river", "Powder River Basin", Resource.COAL,
                44.0, -105.5, 2.0, 2.0, 10, 250, 5.5));
        d.add(new Deposit("shanxi", "Shanxi Coalfield", Resource.COAL,
                37.5, 112.0, 3.0, 2.5, 30, 800, 5.2));
        d.add(new Deposit("bowen", "Bowen Basin", Resource.COAL,
                -22.5, 148.5, 3.0, 2.0, 20, 450, 4.0));
        d.add(new Deposit("silesia", "Upper Silesian Basin", Resource.COAL,
                50.3, 18.9, 1.0, 1.3, 100, 1100, 3.6));

        // --- Oil and gas ------------------------------------------------------------------
        // Placed on real petroleum systems: desert basins, continental shelves and deltas.
        // These are the coordinates the Create: The Factory Must Grow integration reads.
        d.add(new Deposit("ghawar", "Ghawar Field", Resource.OIL,
                25.4, 49.6, 1.6, 1.0, 1500, 2600, 6.0));
        d.add(new Deposit("west_siberia", "West Siberian Basin", Resource.OIL,
                61.0, 73.0, 6.0, 12.0, 1800, 3200, 5.0));
        d.add(new Deposit("volga_urals", "Volga-Ural Province", Resource.OIL,
                55.0, 53.0, 3.5, 5.0, 1200, 2400, 3.4));
        d.add(new Deposit("north_sea", "North Sea Province", Resource.OIL,
                58.0, 2.0, 3.5, 4.0, 2000, 3400, 3.6));
        d.add(new Deposit("gulf_mexico", "Gulf of Mexico Shelf", Resource.OIL,
                27.0, -91.0, 3.0, 5.0, 2000, 4000, 4.2));
        d.add(new Deposit("permian", "Permian Basin", Resource.OIL,
                31.8, -102.5, 2.0, 2.5, 1500, 3000, 4.6));
        d.add(new Deposit("niger_delta", "Niger Delta", Resource.OIL,
                4.8, 6.0, 1.6, 2.2, 1200, 3000, 4.0));
        d.add(new Deposit("maracaibo", "Lake Maracaibo", Resource.OIL,
                9.8, -71.5, 1.2, 1.2, 800, 2400, 4.4));
        d.add(new Deposit("sahara_hassi", "Hassi Messaoud", Resource.OIL,
                31.7, 6.1, 1.8, 2.2, 2000, 3400, 3.8));
        d.add(new Deposit("yamal_gas", "Yamal Gas Province", Resource.NATURAL_GAS,
                70.0, 70.0, 3.5, 8.0, 1000, 2200, 5.0));
        d.add(new Deposit("qatar_gas", "North Field", Resource.NATURAL_GAS,
                26.2, 51.8, 1.4, 1.6, 2500, 3500, 5.8));

        // --- Metals -----------------------------------------------------------------------
        d.add(new Deposit("kursk", "Kursk Magnetic Anomaly", Resource.IRON,
                51.3, 37.0, 2.0, 3.0, 50, 700, 6.0));
        d.add(new Deposit("pilbara", "Pilbara", Resource.IRON,
                -22.5, 118.5, 3.0, 4.5, 0, 300, 6.2));
        d.add(new Deposit("carajas", "Carajas", Resource.IRON,
                -6.0, -50.2, 1.5, 2.0, 0, 350, 5.4));
        d.add(new Deposit("kiruna", "Kiruna", Resource.IRON,
                67.85, 20.2, 0.6, 1.2, 100, 1400, 4.0));
        d.add(new Deposit("atacama_cu", "Atacama Copper Belt", Resource.COPPER,
                -23.5, -68.8, 4.0, 1.5, 0, 900, 6.0));
        d.add(new Deposit("zambia_cu", "Central African Copperbelt", Resource.COPPER,
                -12.5, 27.5, 2.0, 3.0, 50, 1000, 5.0));
        d.add(new Deposit("norilsk", "Norilsk-Talnakh", Resource.NICKEL,
                69.4, 88.2, 1.0, 2.0, 200, 1800, 5.6));
        d.add(new Deposit("sudbury", "Sudbury Basin", Resource.NICKEL,
                46.6, -81.2, 0.7, 1.0, 300, 2400, 4.8));
        d.add(new Deposit("witwatersrand", "Witwatersrand", Resource.GOLD,
                -26.4, 27.4, 1.2, 2.0, 500, 3800, 5.0));
        d.add(new Deposit("kalgoorlie", "Kalgoorlie", Resource.GOLD,
                -30.75, 121.5, 1.0, 1.5, 100, 1500, 3.8));
        d.add(new Deposit("yakutia_dia", "Yakutian Kimberlites", Resource.DIAMOND,
                66.4, 112.4, 1.5, 3.0, 200, 1200, 4.0));
        d.add(new Deposit("kimberley", "Kimberley Pipes", Resource.DIAMOND,
                -28.7, 24.8, 0.8, 1.2, 150, 1000, 3.6));
        d.add(new Deposit("weipa", "Weipa Bauxite", Resource.BAUXITE,
                -12.7, 142.0, 1.2, 1.5, 0, 60, 5.0));
        d.add(new Deposit("guinea_bauxite", "Boke Bauxite", Resource.BAUXITE,
                11.0, -14.2, 1.5, 2.0, 0, 70, 5.4));
        d.add(new Deposit("bolivia_tin", "Bolivian Tin Belt", Resource.TIN,
                -18.5, -66.5, 3.0, 1.2, 100, 900, 4.0));
        d.add(new Deposit("saskatchewan_u", "Athabasca Basin", Resource.URANIUM,
                58.0, -105.0, 1.5, 3.0, 200, 800, 4.4));
        d.add(new Deposit("saskatchewan_k", "Saskatchewan Potash", Resource.POTASH,
                51.5, -105.0, 1.5, 3.5, 900, 1700, 5.0));
        d.add(new Deposit("verkhnekamsk", "Verkhnekamsk Salt", Resource.SALT,
                59.4, 56.8, 0.8, 1.4, 300, 800, 5.2));

        return List.copyOf(d);
    }

    /**
     * Every deposit overlapping a position, strongest first. Returns an empty list almost
     * everywhere, which is the point: the lookup is a cheap ellipse test per deposit and the
     * table is small enough that scanning it per chunk costs nothing measurable.
     */
    public static List<Deposit> at(List<Deposit> all, double lat, double lon) {
        List<Deposit> hits = new ArrayList<>(2);
        for (Deposit dep : all) {
            if (dep.densityAt(lat, lon) > 0.0) hits.add(dep);
        }
        hits.sort((a, b) -> Double.compare(
                b.densityAt(lat, lon) * b.richness(),
                a.densityAt(lat, lon) * a.richness()));
        return hits;
    }
}
