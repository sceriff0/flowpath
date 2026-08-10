package qupath.ext.flowpath.umap.model;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.lib.objects.PathObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable result of a UMAP computation.
 * Stores 2D embedding coordinates with back-references to PathObjects.
 */
public class UmapResult {

    private final double[] umapX;
    private final double[] umapY;
    private final PathObject[] objects;
    private final String[] markerNames;
    private final UmapParameters params;

    public UmapResult(double[] umapX, double[] umapY, PathObject[] objects,
                      String[] markerNames, UmapParameters params) {
        Objects.requireNonNull(umapX, "umapX");
        Objects.requireNonNull(umapY, "umapY");
        Objects.requireNonNull(objects, "objects");
        Objects.requireNonNull(markerNames, "markerNames");
        if (umapX.length != umapY.length || umapX.length != objects.length) {
            throw new IllegalArgumentException(
                    "Array length mismatch: umapX=%d, umapY=%d, objects=%d"
                            .formatted(umapX.length, umapY.length, objects.length));
        }
        this.umapX = umapX;
        this.umapY = umapY;
        this.objects = objects;
        this.markerNames = markerNames;
        this.params = params;
    }

    public double[] getUmapX() { return umapX.clone(); }
    public double[] getUmapY() { return umapY.clone(); }
    public PathObject[] getObjects() { return objects.clone(); }
    public String[] getMarkerNames() { return markerNames.clone(); }
    public UmapParameters getParams() { return params; }
    public int size() { return umapX.length; }

    // --- Raw accessors (no defensive copy) ---
    //
    // The clone-on-get accessors above protect callers from accidental mutation but
    // cost O(N) per call. At realistic dataset sizes (millions of cells) this dominates
    // hot render paths that re-read the embedding every frame. The raw accessors below
    // return the backing arrays directly so the render path can iterate without
    // allocating. Callers MUST treat the returned arrays as read-only — mutation will
    // corrupt the result and any other consumers that share the reference.

    /**
     * @return the backing UMAP X-coordinate array — DO NOT MUTATE. Use
     *         {@link #getUmapX()} for a defensive copy if you need to mutate.
     */
    public double[] getUmapXRaw() { return umapX; }

    /**
     * @return the backing UMAP Y-coordinate array — DO NOT MUTATE. Use
     *         {@link #getUmapY()} for a defensive copy if you need to mutate.
     */
    public double[] getUmapYRaw() { return umapY; }

    /**
     * @return the backing PathObject array — DO NOT MUTATE the array structure
     *         (slot replacement). Use {@link #getObjects()} for a defensive copy if
     *         you need to mutate. The PathObjects themselves are mutable (e.g.
     *         setPathClass), and that is by design.
     */
    public PathObject[] getObjectsRaw() { return objects; }

    /**
     * @return the backing marker-name array — DO NOT MUTATE. Use
     *         {@link #getMarkerNames()} for a defensive copy if you need to mutate.
     */
    public String[] getMarkerNamesRaw() { return markerNames; }

    /**
     * Export cell data to CSV in FlowPath-compatible format with UMAP coordinates
     * and population tags.
     * <p>
     * Columns: cell_id, phenotype, population, centroid_x, centroid_y, umap_x, umap_y,
     * then per-marker: {marker}_raw, {marker}_zscore.
     * <p>
     * When cells have been tagged via polygon gating, the PathClass has the form
     * "BasePhenotype: TagName". This method splits that into phenotype and population columns.
     *
     * @param file           destination CSV file
     * @param cellIndex      the cell index containing marker data and centroids
     * @param markerStats    per-marker statistics for z-score computation (may be null)
     * @param populationTags list of population tags (may be null or empty)
     */
    public void exportToCsv(File file, CellIndex cellIndex, MarkerStats markerStats,
                            List<PopulationTag> populationTags) throws IOException {
        if (cellIndex.size() != umapX.length) {
            throw new IllegalArgumentException(
                    "CellIndex size %d does not match UmapResult size %d"
                            .formatted(cellIndex.size(), umapX.length));
        }
        boolean hasTags = populationTags != null && !populationTags.isEmpty();
        String[] markers = cellIndex.getMarkerNames();

        // Pre-fetch all marker value columns once. Raw (non-cloning) accessor: this
        // loop only reads, and cloning every column here would duplicate the whole
        // dataset just to write it out.
        double[][] allMarkerValues = new double[markers.length][];
        for (int m = 0; m < markers.length; m++) {
            allMarkerValues[m] = cellIndex.getMarkerValuesRaw(m);
        }

        try (var writer = new BufferedWriter(new FileWriter(file))) {
            // Header
            writer.write("cell_id,phenotype,population,centroid_x,centroid_y,umap_x,umap_y");
            for (String marker : markers) {
                String safe = escapeCsv(marker);
                writer.write("," + safe + "_raw");
                writer.write("," + safe + "_zscore");
            }
            writer.newLine();

            // One row per cell
            for (int i = 0; i < umapX.length; i++) {
                var pc = objects[i].getPathClass();
                String fullLabel = pc != null ? pc.toString() : "Unclassified";

                String phenotype;
                String population = "";
                int sepIdx = fullLabel.lastIndexOf(": ");
                if (hasTags && sepIdx >= 0) {
                    String possibleTag = fullLabel.substring(sepIdx + 2);
                    boolean isKnownTag = populationTags.stream()
                            .anyMatch(t -> t.name().equals(possibleTag));
                    if (isKnownTag) {
                        phenotype = fullLabel.substring(0, sepIdx);
                        population = possibleTag;
                    } else {
                        phenotype = fullLabel;
                    }
                } else {
                    phenotype = fullLabel;
                }

                // cell_id, phenotype, population
                writer.write(String.valueOf(i));
                writer.write(',');
                writer.write(escapeCsv(phenotype));
                writer.write(',');
                writer.write(escapeCsv(population));

                // centroid_x, centroid_y
                writer.write(',' + fmt(cellIndex.getCentroidX(i)));
                writer.write(',' + fmt(cellIndex.getCentroidY(i)));

                // umap_x, umap_y
                writer.write(',' + fmt(umapX[i]));
                writer.write(',' + fmt(umapY[i]));

                // Per-marker: raw, zscore
                for (int m = 0; m < markers.length; m++) {
                    double raw = allMarkerValues[m][i];
                    double zscore;
                    if (Double.isNaN(raw) || markerStats == null) {
                        zscore = Double.NaN;
                    } else {
                        zscore = markerStats.toZScore(markers[m], raw);
                    }
                    writer.write(',' + fmt(raw));
                    writer.write(',' + fmt(zscore));
                }
                writer.newLine();
            }
        }
    }

    /** Format a double for CSV; NaN → empty string. */
    private static String fmt(double val) {
        return Double.isNaN(val) ? "" : String.format(Locale.US, "%.4f", val);
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
