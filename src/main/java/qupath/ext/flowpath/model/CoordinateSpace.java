package qupath.ext.flowpath.model;

/**
 * The coordinate space a cell position is expressed in.
 * <p>
 * Two spaces flow through FlowPath simultaneously, from the same {@code cells.geojson}:
 * <ul>
 *   <li>{@link #PIXELS} — full-resolution (level 0) image pixels. This is where the
 *       polygon {@code geometry} lives, so it is where every {@link qupath.lib.roi.interfaces.ROI}
 *       lives, and therefore the space QuPath annotations are drawn in. MIRAGE writes
 *       its contours in QuPath's "+0.5 corner-of-pixel" convention
 *       ({@code bin/extract_cell_properties.py}), and no downsample, crop or tile
 *       offset reaches them — tiled registration is fully resolved first.</li>
 *   <li>{@link #MICRONS} — micrometres, the space of the {@code "Centroid X µm"} /
 *       {@code "Centroid Y µm"} measurements, written by {@code bin/export_geojson.py}
 *       as {@code (x_px + 0.5) * pixel_size}.</li>
 * </ul>
 * Before this enum existed both spaces were plain {@code double}s and nothing in the
 * codebase said which was which: {@code CellIndex} resolved a centroid from the
 * measurement (µm) or the ROI (px) <em>independently per axis</em>, and the CSV exporter
 * wrote the result under an unlabelled {@code centroid_x} header. A partially populated
 * export could therefore produce one column holding µm in some rows and pixels in others.
 * {@link CellGeometry} exists to make that unrepresentable.
 *
 * @see CellGeometry
 */
public enum CoordinateSpace {

    /** Micrometres. The unit of MIRAGE's {@code "Centroid X µm"} measurement. */
    MICRONS("µm"),

    /** Full-resolution (level 0) image pixels. The unit of every QuPath {@code ROI}. */
    PIXELS("px");

    private final String unit;

    CoordinateSpace(String unit) {
        this.unit = unit;
    }

    /** Short unit label, e.g. {@code "µm"} — suitable for a column header or a tooltip. */
    public String unit() {
        return unit;
    }
}
