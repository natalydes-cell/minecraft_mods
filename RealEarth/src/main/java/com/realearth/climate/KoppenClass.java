package com.realearth.climate;

/**
 * Koppen-Geiger climate classes, in the numbering used by Beck et al. (2018).
 *
 * <p>This enum is the single place where "what is the climate here" turns into concrete numbers.
 * Biome choice, rainfall frequency and duration, storm likelihood, snow, Cold Sweat temperature
 * bands and crop fertility all read from here, so the Sahara being dry and the Amazon being
 * relentlessly wet is one fact stated once rather than six rules scattered around the codebase.
 *
 * @param baseTempC       annual mean air temperature at sea level, degC
 * @param seasonalSwingC  half the difference between the warmest and coldest month, degC
 * @param annualRainMm    annual precipitation, mm
 * @param wetFraction     fraction of days with measurable precipitation, 0..1
 * @param stormFactor     relative frequency of thunder over ordinary rain, 0..1
 * @param fertility       crop growth multiplier, 1.0 = vanilla farmland
 */
public enum KoppenClass {
    // Index 0 is reserved for "no data" / ocean.
    OCEAN           ( 0, 15.0, 6.0, 1100,  0.32, 0.30, 0.0),

    AF_TROPICAL_RAIN( 1, 26.5, 1.5, 2800,  0.62, 0.75, 1.25),
    AM_TROPICAL_MONS( 2, 26.0, 2.0, 2100,  0.48, 0.70, 1.20),
    AW_TROPICAL_SAV ( 3, 25.5, 3.5, 1100,  0.28, 0.65, 0.95),

    BWH_DESERT_HOT  ( 4, 28.0, 9.0,   70,  0.03, 0.15, 0.10),
    BWK_DESERT_COLD ( 5, 10.0,17.0,   110, 0.05, 0.12, 0.15),
    BSH_STEPPE_HOT  ( 6, 24.0, 9.0,   350, 0.10, 0.30, 0.55),
    BSK_STEPPE_COLD ( 7,  8.0,16.0,   330, 0.12, 0.25, 0.70),

    CSA_MED_HOT     ( 8, 18.0, 9.0,   550, 0.14, 0.25, 0.85),
    CSB_MED_WARM    ( 9, 14.5, 7.5,   650, 0.17, 0.22, 0.90),
    CSC_MED_COLD    (10,  8.0, 6.5,   700, 0.20, 0.18, 0.60),
    CWA_HUMID_SUBTR (11, 19.5, 11.0, 1250, 0.32, 0.60, 1.10),
    CWB_SUBTR_HIGH  (12, 15.0, 6.0,  1000, 0.30, 0.50, 1.00),
    CWC_SUBTR_COLD  (13,  7.0, 6.5,   850, 0.28, 0.35, 0.55),
    CFA_HUMID_SUBTR (14, 17.5, 11.5, 1200, 0.33, 0.55, 1.15),
    CFB_OCEANIC     (15, 10.5, 7.5,   900, 0.42, 0.20, 1.05),
    CFC_SUBPOLAR_OC (16,  6.0, 5.5,  1300, 0.50, 0.12, 0.65),

    DSA_CONT_DRYSUM (17,  8.0, 15.0,  450, 0.16, 0.30, 0.70),
    DSB_CONT_DRYSUM (18,  5.0, 14.0,  500, 0.19, 0.25, 0.70),
    DSC_CONT_DRYSUM (19, -2.0, 15.0,  400, 0.20, 0.18, 0.40),
    DSD_CONT_DRYSUM (20,-12.0, 22.0,  300, 0.18, 0.10, 0.20),
    DWA_CONT_DRYWIN (21,  9.0, 18.0,  650, 0.22, 0.45, 0.85),
    DWB_CONT_DRYWIN (22,  3.0, 19.0,  600, 0.24, 0.35, 0.80),
    DWC_CONT_DRYWIN (23, -5.0, 22.0,  450, 0.24, 0.20, 0.45),
    DWD_CONT_DRYWIN (24,-16.0, 26.0,  300, 0.22, 0.10, 0.15),
    DFA_CONT_HUMID  (25,  9.5, 16.0,  850, 0.30, 0.45, 1.00),
    DFB_CONT_HUMID  (26,  5.5, 15.5,  750, 0.33, 0.35, 0.95),
    DFC_SUBARCTIC   (27, -3.5, 19.0,  500, 0.33, 0.15, 0.45),
    DFD_SUBARCTIC_SV(28,-14.0, 25.0,  350, 0.30, 0.08, 0.15),

    ET_TUNDRA       (29, -7.0, 14.0,  300, 0.30, 0.04, 0.08),
    EF_ICE_CAP      (30,-35.0, 18.0,   80, 0.28, 0.01, 0.0);

    private final int index;
    private final double baseTempC;
    private final double seasonalSwingC;
    private final double annualRainMm;
    private final double wetFraction;
    private final double stormFactor;
    private final double fertility;

    KoppenClass(int index, double baseTempC, double seasonalSwingC, double annualRainMm,
                double wetFraction, double stormFactor, double fertility) {
        this.index = index;
        this.baseTempC = baseTempC;
        this.seasonalSwingC = seasonalSwingC;
        this.annualRainMm = annualRainMm;
        this.wetFraction = wetFraction;
        this.stormFactor = stormFactor;
        this.fertility = fertility;
    }

    private static final KoppenClass[] BY_INDEX = new KoppenClass[31];
    static {
        for (KoppenClass k : values()) BY_INDEX[k.index] = k;
    }

    public static KoppenClass byIndex(int index) {
        if (index < 0 || index >= BY_INDEX.length) return OCEAN;
        KoppenClass k = BY_INDEX[index];
        return k == null ? OCEAN : k;
    }

    public int index()             { return index; }
    public double baseTempC()      { return baseTempC; }
    public double seasonalSwingC() { return seasonalSwingC; }
    public double annualRainMm()   { return annualRainMm; }
    public double wetFraction()    { return wetFraction; }
    public double stormFactor()    { return stormFactor; }
    public double fertility()      { return fertility; }

    public boolean isDesert()  { return this == BWH_DESERT_HOT || this == BWK_DESERT_COLD; }
    public boolean isPolar()   { return this == ET_TUNDRA || this == EF_ICE_CAP; }
    public boolean isTropical(){ return index >= 1 && index <= 3; }

    /**
     * Air temperature in degC for this climate at a given day of the year.
     *
     * <p>{@code northern} flips the seasonal phase, which is what makes January summer in
     * Patagonia and winter in Siberia - the difference the whole ice, flood and Cold Sweat chain
     * depends on.
     */
    public double temperatureAt(int dayOfYear, boolean northern) {
        double phase = (dayOfYear - (northern ? 200 : 17)) / 365.0 * 2.0 * Math.PI;
        return baseTempC + seasonalSwingC * Math.cos(phase);
    }
}
