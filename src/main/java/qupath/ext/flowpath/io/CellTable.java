package qupath.ext.flowpath.io;

import qupath.ext.flowpath.model.CellGeometry;
import qupath.ext.flowpath.model.CellIndex;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

/**
 * <b>The identity block every FlowPath CSV emits</b> — one definition of how a row names
 * the cell it describes, and in which units it places it.
 * <p>
 * FlowPath writes two per-cell tables: {@code gate_pheno.csv} from
 * {@link PhenotypeCsvExporter} and {@code umap_coordinates.csv} from
 * {@code UmapResult.exportToCsv}. They were written years apart and had drifted into two
 * different schemas that could not be joined to each other:
 * <ul>
 *   <li>{@code centroid_x} meant <em>micrometres</em> in one file and
 *       <em>whatever space the measurement arrived in</em> in the other, under the same
 *       column name and with the unit stated in neither;</li>
 *   <li>only one of them carried {@code label}, the segmentation key that makes the join
 *       back to MIRAGE exact rather than nearest-neighbour;</li>
 *   <li>one reported per-<em>resolved-column</em> intensities ({@code CD3_Nucleus_Median_raw}),
 *       the other per-<em>bare-marker</em> ({@code CD3_raw}), so their z-scores were over
 *       different columns.</li>
 * </ul>
 * The unit divergence was the dangerous one. It agreed by luck on MIRAGE input (MIRAGE
 * emits µm, so the source space <em>is</em> µm) and disagreed on the AnnoMask on-ramp,
 * where the image is calibrated but centroids arrive in pixels. Joining the two files
 * there produced a plausible-looking wrong answer and nothing threw.
 *
 * <h2>Units are in the column names</h2>
 * {@code centroid_x_px} / {@code centroid_y_px} name their space. {@code centroid_x} /
 * {@code centroid_y} keep their historical bare names <b>and are always micrometres</b>,
 * because that is a cross-repo contract: {@code mirage/bin/join_flowpath.py} inverts them
 * as {@code x_px = centroid_x / pixel_size - 0.5} and hard-fails if they are absent. The
 * bare pair is therefore an alias for the µm pair, kept for MIRAGE, and the explicit
 * suffixes are what a new consumer should read.
 *
 * @see PhenotypeCsvExporter
 */
public final class CellTable {

    private CellTable() {}

    /**
     * The identity and geometry columns, in order, shared by every FlowPath per-cell CSV.
     * A writer appends its own columns after these; column <em>order</em> carries no
     * meaning to any consumer (both {@code join_flowpath.py} and pandas address by name),
     * so appending is always safe.
     */
    private static final String HEADER_HEAD = "cell_id";
    private static final String HEADER_TAIL =
            "phenotype,centroid_x,centroid_y,centroid_x_px,centroid_y_px"
            + ",area,perimeter,eccentricity,solidity";

    /**
     * Write the shared header block.
     *
     * @param withLabel emit the {@code label} column. Pass {@link CellIndex#hasLabels()}:
     *                  an all-blank {@code label} column is <em>worse</em> than no column,
     *                  because {@code join_flowpath.py} branches on the column's
     *                  <em>presence</em> and would take the exact-join path to match
     *                  nothing, where its absence correctly selects the centroid fallback.
     */
    public static void writeIdentityHeader(Writer w, boolean withLabel) throws IOException {
        w.write(HEADER_HEAD);
        if (withLabel) w.write(",label");
        w.write(',');
        w.write(HEADER_TAIL);
    }

    /**
     * Write the shared row block for cell {@code i}.
     *
     * @param phenotype the cell's phenotype; escaped here, so callers pass it raw
     */
    public static void writeIdentityRow(Writer w, CellIndex index, int i,
                                        boolean withLabel, String phenotype) throws IOException {
        CellGeometry geometry = index.geometry();

        w.write(String.valueOf(i));
        if (withLabel) {
            w.write(',');
            w.write(fmtLabel(index.getLabel(i)));
        }
        w.write(',');
        w.write(escape(phenotype != null ? phenotype : ""));

        // Micrometres whenever micrometres can be known -- measured, or derived from the
        // ROI via the image's calibration. Only an uncalibrated image with no µm
        // measurement leaves these in the source space, which is what they have always
        // been in that case; blanking them would lose the position entirely.
        w.write(',');
        w.write(fmt(micronsOrSource(geometry, i, true)));
        w.write(',');
        w.write(fmt(micronsOrSource(geometry, i, false)));
        // The pixel space, stated explicitly, so a consumer never has to invert the
        // calibration to get back to mask coordinates.
        w.write(',');
        w.write(fmt(geometry.pixelsX(i)));
        w.write(',');
        w.write(fmt(geometry.pixelsY(i)));

        w.write(',');
        w.write(fmt(index.getArea(i)));
        w.write(',');
        w.write(fmt(index.getPerimeter(i)));
        w.write(',');
        w.write(fmt(index.getEccentricity(i)));
        w.write(',');
        w.write(fmt(index.getSolidity(i)));
    }

    /**
     * The micrometre coordinate, or the raw source coordinate when micrometres cannot be
     * derived (an uncalibrated image whose cells carry no µm centroid). Never mixes the
     * two <em>within</em> a column: {@link CellGeometry} decides one source space for the
     * whole index, so this resolves to µm for every row or to pixels for every row.
     */
    static double micronsOrSource(CellGeometry geometry, int i, boolean xAxis) {
        double microns = xAxis ? geometry.micronsX(i) : geometry.micronsY(i);
        if (!Double.isNaN(microns)) return microns;
        return xAxis ? geometry.sourceX(i) : geometry.sourceY(i);
    }

    /**
     * Format a double for CSV; NaN becomes an empty field.
     * <p>
     * {@link Locale#US} is not optional here: this JVM's default locale is {@code en_IT},
     * whose decimal separator is a comma. {@code %.4f} under the default locale emits
     * {@code 1,2345} — a field separator in the middle of a number — which every consumer
     * reads as two columns.
     */
    public static String fmt(double val) {
        return Double.isNaN(val) ? "" : String.format(Locale.US, "%.4f", val);
    }

    /**
     * Format a segmentation label: no decimal part when the value is integral, which it
     * always is for a label. {@code join_flowpath.py} does {@code int(v)} on the column,
     * and an integer reads as an identity rather than as a measurement.
     */
    public static String fmtLabel(double val) {
        if (Double.isNaN(val)) return "";
        if (val == Math.rint(val) && !Double.isInfinite(val)) {
            return String.valueOf((long) val);
        }
        return fmt(val);
    }

    /**
     * Escape a value for CSV output: wrapped in double quotes if it contains a comma,
     * a double quote, or a newline.
     */
    public static String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
