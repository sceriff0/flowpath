package qupath.ext.flowpath.umap.model;

import qupath.ext.flowpath.io.CellTable;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.Statistic;
import qupath.lib.objects.PathObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
     * Export the embedding as {@code umap_coordinates.csv}.
     * <p>
     * Columns are {@link CellTable}'s shared identity block -- {@code cell_id},
     * {@code label} (when present), {@code phenotype}, the micrometre and pixel centroid
     * pairs and the morphology fields -- then {@code population}, {@code umap_x},
     * {@code umap_y}, then per resolved column {@code {column}_raw} and
     * {@code {column}_zscore}.
     * <p>
     * <b>This file and {@code gate_pheno.csv} are joinable on {@code label}.</b> They were
     * not before. This writer took its centroids from {@link CellIndex#getCentroidX},
     * which returns {@code CellGeometry.sourceX} -- the space the measurement happened to
     * arrive in -- while {@code PhenotypeCsvExporter} wrote micrometres, under the same
     * {@code centroid_x} name and with the unit recorded in neither file. On MIRAGE input
     * the two agreed by luck, because MIRAGE emits µm and the source space <em>is</em> µm.
     * On the AnnoMask on-ramp, where the image is calibrated but centroids arrive in
     * pixels, they disagreed by the pixel size and nothing threw. {@code CellGeometry}'s
     * own javadoc says to prefer {@code micronsX}/{@code pixelsX} over {@code sourceX};
     * this was the call site that did not.
     * <p>
     * Intensities are read through {@link MeasuredColumn} rather than by panel index, so
     * the {@code _raw}/{@code _zscore} headers and the z-scores behind them are the same
     * columns {@code gate_pheno.csv} reports and the gating actually compared on.
     * <p>
     * When cells have been tagged via polygon gating, the PathClass has the form
     * {@code "BasePhenotype: TagName"}; that is split into {@code phenotype} and
     * {@code population}.
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
        boolean withLabel = cellIndex.hasLabels();

        // Resolve every panel marker to the same default column gate_pheno.csv reports:
        // whole-cell mean, which CellIndex resolves to the bare marker key. Going through
        // MeasuredColumn rather than indexing getMarkerValuesRaw by panel position is what
        // makes the two files' _raw/_zscore columns the same columns -- and it registers
        // each one with MarkerStats on construction, so the z-scores below cannot be the
        // silent 0.0 an unregistered column used to produce.
        List<MeasuredColumn> columns = new ArrayList<>();
        if (markerStats != null) {
            for (String marker : cellIndex.getMarkerNames()) {
                columns.add(cellIndex.column(marker, Compartment.WHOLE_CELL,
                        Statistic.MEAN, markerStats));
            }
        }
        // Without statistics there is nothing to standardise against, so the panel is
        // written raw. Reading the backing arrays directly here is CellIndex's no-copy
        // contract: cloning a 40-marker slide's columns to write them out would duplicate
        // the whole dataset.
        String[] rawMarkers = markerStats == null ? cellIndex.getMarkerNames() : new String[0];
        double[][] rawValues = new double[rawMarkers.length][];
        for (int m = 0; m < rawMarkers.length; m++) {
            rawValues[m] = cellIndex.getMarkerValuesRaw(m);
        }

        try (var writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            CellTable.writeIdentityHeader(writer, cellIndex, withLabel);
            writer.write(",population,umap_x,umap_y");
            for (MeasuredColumn col : columns) {
                // Escape the whole field, suffix included: a marker name containing a
                // comma would otherwise emit `"CD3, clone"_raw`, which is text after a
                // closing quote and not valid CSV.
                String base = col.key().replace(": ", "_");
                writer.write("," + CellTable.escape(base + "_raw"));
                writer.write("," + CellTable.escape(base + "_zscore"));
            }
            for (String marker : rawMarkers) {
                writer.write("," + CellTable.escape(marker + "_raw"));
                writer.write("," + CellTable.escape(marker + "_zscore"));
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

                CellTable.writeIdentityRow(writer, cellIndex, i, withLabel, phenotype);

                writer.write(',');
                writer.write(CellTable.escape(population));
                writer.write(',' + CellTable.fmt(umapX[i]));
                writer.write(',' + CellTable.fmt(umapY[i]));

                for (MeasuredColumn col : columns) {
                    double raw = col.valueAt(i);
                    double zscore = (Double.isNaN(raw) || !col.hasSpread())
                            ? Double.NaN
                            : col.toZScore(raw);
                    writer.write(',' + CellTable.fmt(raw));
                    writer.write(',' + CellTable.fmt(zscore));
                }
                for (int m = 0; m < rawMarkers.length; m++) {
                    writer.write(',' + CellTable.fmt(rawValues[m][i]));
                    writer.write(",");
                }
                writer.newLine();
            }
        }
    }
}
