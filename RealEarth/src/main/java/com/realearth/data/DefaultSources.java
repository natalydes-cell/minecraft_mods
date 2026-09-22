package com.realearth.data;

import java.util.List;

/**
 * The out-of-the-box source list, written to {@code config/realearth/sources.json} on first run.
 *
 * <p>Every URL here is a real, verified, direct download from the organisation that publishes the
 * data - no mirrors, no scraping, no third parties. All six are open for redistribution under
 * their own terms, and the attribution string on each is the citation those terms require.
 *
 * <p>Nothing ships inside the jar. The mod writes this manifest and the operator either runs the
 * importer or drops converted tiles in by hand. That keeps the jar at a hundred kilobytes instead
 * of several gigabytes, and keeps licence compliance where it belongs.
 *
 * <p>Edit the generated json to point anywhere else - a national LIDAR survey for one country, a
 * mirror inside your own network, or a folder you filled yourself.
 */
public final class DefaultSources {

    private DefaultSources() {}

    public static List<GeoSource> defaults() {
        return List.of(
                // ---------------------------------------------------------------------------
                // ETOPO 2022, NOAA NCEI. 15 arc-second global relief: land elevation and ocean
                // bathymetry on one continuous surface, which is what lets a coastline emerge at
                // the zero crossing instead of being drawn as a separate feature.
                //
                // Chosen over GEBCO, which covers the same ground, because ETOPO ships as 272
                // tiles of 15x15 degrees rather than one 7 GB file. That means a server owner can
                // fetch only the continents they care about, and the importer never has to hold a
                // whole planet in memory.
                //
                // "surface" is the ice-surface model: Antarctica and Greenland are the top of the
                // ice sheet, which is what you actually walk on. The "bed" variant is the rock
                // underneath and would put you 3 km below the real surface at the South Pole.
                //
                // 15 arc-seconds is 240 samples per degree - about 460 m at the equator, so at
                // 1:2 scale one sample covers 230 blocks. Public domain, US Government work.
                new GeoSource("elevation", GeoSource.Kind.ELEVATION,
                        "https://www.ngdc.noaa.gov/mgg/global/relief/ETOPO2022/data/15s/"
                                + "15s_surface_elev_gtif/ETOPO_2022_v1_15s_{NS}{lat}{EW}{lon}_surface.tif",
                        "elevation", 512, 240.0, 1.0, 0.0, -32768,
                        "NOAA NCEI (2022) ETOPO 2022 15 Arc-Second Global Relief Model. "
                                + "doi:10.25921/fd45-gt74"),

                // ---------------------------------------------------------------------------
                // Koppen-Geiger, Beck et al. (2023) V3, 1 km. The climate backbone: biome choice,
                // rainfall frequency, storm likelihood, Cold Sweat bands and crop fertility all
                // derive from this class plus latitude.
                //
                // The integer classes run 1..30 in the standard order - 1 Af, 4 BWh, 27 Dfc,
                // 30 EF - which is exactly the numbering KoppenClass uses, so the raster values
                // need no remapping at all.
                //
                // One zip from figshare containing GeoTIFFs at several resolutions; the importer
                // takes the 1 km one. CC BY 4.0, commercial use permitted.
                new GeoSource("koppen", GeoSource.Kind.KOPPEN,
                        "https://figshare.com/ndownloader/files/61012822",
                        "koppen", 512, 120.0, 1.0, 0.0, -32768,
                        "Beck, H.E. et al. (2023) High-resolution (1 km) Koppen-Geiger maps for "
                                + "1901-2099 based on constrained CMIP6 projections. CC BY 4.0"),

                // ---------------------------------------------------------------------------
                // WorldClim 2.1, 1970-2000 monthly normals. Refines the Koppen class with real
                // local numbers where they matter - the difference between a coastal and an
                // inland site inside the same climate class.
                //
                // Optional: without it the Koppen class supplies the numbers on its own. 10
                // arc-minutes is deliberately coarse (6 samples/degree, ~18 km); climate
                // normals genuinely do not vary faster than that, and the 30-second version is
                // 20 GB for no visible gain at world scale.
                //
                // Stored as 0.1 degC, hence the 0.1 scale.
                new GeoSource("temperature", GeoSource.Kind.TEMPERATURE,
                        "https://geodata.ucdavis.edu/climate/worldclim/2_1/base/wc2.1_10m_tavg.zip",
                        "temperature", 512, 6.0, 0.1, 0.0, -32768,
                        "Fick, S.E. & Hijmans, R.J. (2017) WorldClim 2.1 monthly average "
                                + "temperature, 1970-2000"),

                new GeoSource("precipitation", GeoSource.Kind.PRECIPITATION,
                        "https://geodata.ucdavis.edu/climate/worldclim/2_1/base/wc2.1_10m_prec.zip",
                        "precipitation", 512, 6.0, 1.0, 0.0, -32768,
                        "Fick, S.E. & Hijmans, R.J. (2017) WorldClim 2.1 monthly precipitation, "
                                + "1970-2000"),

                // ---------------------------------------------------------------------------
                // GLiM, the Global Lithological Map, gridded to 0.5 degrees. Decides which kind
                // of cave forms: limestone dissolves into long horizontal galleries, granite only
                // fractures, basalt gets lava tubes.
                //
                // Half a degree is about 55 km, which sounds hopeless and is fine - a geological
                // province is hundreds of kilometres across, and nothing finer would change which
                // cave type you get. The full vector database exists at 1:3,750,000 but is a
                // 1.2-million-polygon geodatabase, which is a different project.
                //
                // Optional: without it RealisticCaves infers rock type from elevation, which gets
                // the common cases right.
                //
                // The URL is PANGAEA's persistent handle rather than a direct file path: PANGAEA
                // reorganises its store, and the handle is the identifier they guarantee. It is
                // 37 kB - the whole planet's surface geology fits in less than one ETOPO tile.
                new GeoSource("geology", GeoSource.Kind.GEOLOGY,
                        "https://hdl.handle.net/10013/epic.39939.d001",
                        "geology", 256, 2.0, 1.0, 0.0, -32768,
                        "Hartmann, J. & Moosdorf, N. (2012) Global Lithological Map Database "
                                + "v1.0. doi:10.1594/PANGAEA.788537. CC BY 3.0"),

                // ---------------------------------------------------------------------------
                // Land cover has no default URL, on purpose.
                //
                // ESA WorldCover is the obvious candidate and it is 10 m - several hundred
                // gigabytes for the globe, to decide whether a block gets grass or sand on a
                // world where one sample already covers 230 blocks. The cost is absurd and the
                // benefit is nil.
                //
                // SurfaceDresser derives ground cover from climate, slope and elevation instead,
                // which is both cheaper and more consistent with the terrain it sits on. Point
                // this at a regional dataset if you want real land cover for one area.
                new GeoSource("landcover", GeoSource.Kind.LANDCOVER,
                        null, "landcover", 512, 120.0, 1.0, 0.0, 0,
                        "ESA WorldCover (optional, not downloaded by default)")
        );
    }
}
