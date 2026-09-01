package qupath.ext.flowpath.model;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassTools;
import qupath.lib.roi.interfaces.ROI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Which annotated region each cell belongs to — the annotation filter's answer, widened
 * from "in or out" to "in <em>which</em>".
 *
 * <h2>Why not a boolean[]</h2>
 * <p>The filter used to produce a bare {@code boolean[]}: one bit per cell, true if the
 * cell's centroid fell inside any annotation. That bit cannot express which region a cell
 * came from, so the question multi-region annotation is usually asked for — does this
 * population differ between tumour core and invasive margin? — was not merely
 * unimplemented but <em>unrepresentable</em>. Widening the type is the whole fix; the
 * geometry underneath is unchanged.
 *
 * <h2>What counts as a region</h2>
 * <ul>
 *   <li><b>Include regions</b> are annotations that enclose an area and are not classified
 *       with an ignored class. A cell is assigned to the first one containing it.</li>
 *   <li><b>Exclude regions</b> are annotations carrying a QuPath <em>ignored</em>
 *       classification — the {@code Ignore*} class, or any class whose name ends in
 *       {@code *}, as decided by {@link PathClassTools#isIgnoredClass}. Cells inside one
 *       are dropped, whichever include region they also fall in. This is how necrosis,
 *       tissue folds and artefact regions are subtracted, and it reuses QuPath's own
 *       convention rather than inventing a FlowPath-specific marker.</li>
 *   <li><b>Non-area annotations</b> — lines and points — are skipped and counted in
 *       {@link #droppedNonArea()}. They enclose nothing, so {@code ROI.contains} is false
 *       everywhere; letting them through meant that turning the filter on with only a
 *       stray point annotation present excluded <em>every</em> cell, and the only symptom
 *       was empty histograms.</li>
 *   <li>Exclude regions with no include region alongside them mean "everything except
 *       these": the whole image becomes one implicit region, minus the subtractions.</li>
 * </ul>
 *
 * <h2>Overlaps</h2>
 * <p>Include regions are tested in annotation order and the first match wins, so a cell
 * belongs to exactly one region and the per-region counts sum to the included total.
 * Overlapping annotations are therefore resolved, not double-counted.
 *
 * <p>Coordinates are level-0 <b>pixels</b> throughout — cell centroids and annotation
 * geometry are both in that space, as {@code CellGeometry} requires. A cell is placed by
 * its centroid, so one straddling a boundary falls on the side its centre does.
 */
public final class RegionMask {

    private final String[] regionNames;
    private final int[] regionOf;
    private final boolean[] included;
    private final int[] regionCounts;
    private final ROI[] regionRois;
    /** The {@code Ignore*} annotations subtracted from every region. Needed for area. */
    private final ROI[] excludeRois;
    private final int droppedNonArea;
    private final int excludeRegionCount;
    private final int excludedByRegion;

    private RegionMask(String[] regionNames, int[] regionOf, boolean[] included,
                       int[] regionCounts, ROI[] regionRois, ROI[] excludeRois,
                       int droppedNonArea, int excludeRegionCount, int excludedByRegion) {
        this.regionNames = regionNames;
        this.regionOf = regionOf;
        this.included = included;
        this.regionCounts = regionCounts;
        this.regionRois = regionRois;
        this.excludeRois = excludeRois;
        this.droppedNonArea = droppedNonArea;
        this.excludeRegionCount = excludeRegionCount;
        this.excludedByRegion = excludedByRegion;
    }

    /** Name of the implicit region used when only exclusion annotations are present. */
    public static final String WHOLE_IMAGE = "Whole image";

    /**
     * Assign every cell in {@code index} to one of {@code annotations}, or to none.
     *
     * @param annotations the annotations to filter by, in the order they should be tested
     */
    public static RegionMask compute(CellIndex index, List<PathObject> annotations) {
        int n = index.size();

        List<ROI> includes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<ROI> excludes = new ArrayList<>();
        int dropped = 0;

        for (PathObject ann : annotations) {
            ROI roi = ann == null ? null : ann.getROI();
            if (roi == null) continue;
            if (!roi.isArea()) {
                dropped++;
                continue;
            }
            // The null check is load-bearing: PathClassTools.isIgnoredClass(null) answers
            // *true*, so without it every unclassified annotation -- the ordinary case --
            // would be read as a subtraction, and a plain "draw a region and filter by it"
            // would produce an empty population.
            PathClass pathClass = ann.getPathClass();
            if (pathClass != null && PathClassTools.isIgnoredClass(pathClass)) {
                excludes.add(roi);
            } else {
                includes.add(roi);
                names.add(nameOf(ann, includes.size()));
            }
        }

        if (includes.isEmpty() && excludes.isEmpty()) {
            return new RegionMask(new String[0], filled(n, -1), new boolean[n],
                    new int[0], new ROI[0], new ROI[0], dropped, 0, 0);
        }

        // Parallel to names/includes as they stood before the implicit-whole-image name
        // (if any) is appended below, so this is exactly the include ROI each region name
        // came from -- until the one case with no such ROI, handled next.
        List<ROI> regionRoisList = new ArrayList<>(includes);

        // Exclusions with nothing to subtract from mean "the whole image, minus these".
        boolean implicitWhole = includes.isEmpty();
        if (implicitWhole) {
            names.add(WHOLE_IMAGE);
            // No single ROI represents "the whole image, minus the exclusions" -- an area
            // caller must treat this the same as an unknown area, never guess a number.
            regionRoisList.add(null);
        }

        Bounds includeBounds = Bounds.of(includes);
        Bounds excludeBounds = Bounds.of(excludes);

        int[] regionOf = filled(n, -1);
        boolean[] included = new boolean[n];
        int[] counts = new int[names.size()];
        int excludedByRegion = 0;

        for (int i = 0; i < n; i++) {
            PathObject obj = index.getObject(i);
            ROI cellRoi = obj != null ? obj.getROI() : null;
            if (cellRoi == null) continue;
            double cx = cellRoi.getCentroidX();
            double cy = cellRoi.getCentroidY();

            // Which include region, if any? Resolved before exclusion so that a cell
            // dropped by a subtraction is still known to have been inside a region.
            int region = implicitWhole ? 0 : includeBounds.firstContaining(cx, cy);
            if (region < 0) continue;

            if (excludeBounds.firstContaining(cx, cy) >= 0) {
                excludedByRegion++;
                continue;
            }

            regionOf[i] = region;
            included[i] = true;
            counts[region]++;
        }

        return new RegionMask(names.toArray(new String[0]), regionOf, included, counts,
                regionRoisList.toArray(new ROI[0]), excludes.toArray(new ROI[0]),
                dropped, excludes.size(), excludedByRegion);
    }

    /**
     * A set of ROIs with their envelopes hoisted into primitive arrays.
     * <p>
     * {@code ROI.contains} on a polygon is a full point-in-polygon test through JTS, and
     * annotations are typically small regions on a large slide, so the overwhelmingly
     * common answer is "nowhere near". Four comparisons against the envelope settle those
     * cells; measured on 200k cells against four 200-vertex polygons this took the pass
     * from ~286ms to ~8ms. An envelope is by definition a superset of its geometry, so it
     * can only reject points {@code contains} would also have rejected.
     */
    private record Bounds(ROI[] rois, double[] minX, double[] minY, double[] maxX, double[] maxY) {

        static Bounds of(List<ROI> list) {
            int r = list.size();
            ROI[] rois = list.toArray(new ROI[0]);
            double[] minX = new double[r], minY = new double[r];
            double[] maxX = new double[r], maxY = new double[r];
            for (int j = 0; j < r; j++) {
                ROI roi = rois[j];
                minX[j] = roi.getBoundsX();
                minY[j] = roi.getBoundsY();
                maxX[j] = minX[j] + roi.getBoundsWidth();
                maxY[j] = minY[j] + roi.getBoundsHeight();
            }
            return new Bounds(rois, minX, minY, maxX, maxY);
        }

        /** Index of the first ROI containing the point, or -1. */
        int firstContaining(double x, double y) {
            for (int j = 0; j < rois.length; j++) {
                if (x < minX[j] || x > maxX[j] || y < minY[j] || y > maxY[j]) continue;
                if (rois[j].contains(x, y)) return j;
            }
            return -1;
        }
    }

    /** An annotation's display name: its own name, else its classification, else a number. */
    private static String nameOf(PathObject ann, int ordinal) {
        String name = ann.getName();
        if (name != null && !name.isBlank()) return name;
        PathClass pc = ann.getPathClass();
        if (pc != null && pc.getName() != null && !pc.getName().isBlank()) {
            return pc.toString();
        }
        return "Region " + ordinal;
    }

    private static int[] filled(int n, int value) {
        int[] a = new int[n];
        Arrays.fill(a, value);
        return a;
    }

    // ---- accessors ----

    /**
     * Per-cell inclusion, positional against {@code CellIndex.getObjects()} — the same
     * {@code boolean[]} the filter has always produced, so every existing consumer keeps
     * working unchanged.
     */
    public boolean[] included() {
        return included;
    }

    /**
     * Per-cell region index into {@link #regionNames()}, or {@code -1} for a cell in no
     * region (outside them all, or subtracted by an exclusion).
     */
    public int[] regionOf() {
        return regionOf;
    }

    /** Region name for one cell, or {@code null} when it belongs to none. */
    public String regionNameOf(int cell) {
        int r = regionOf[cell];
        return r < 0 ? null : regionNames[r];
    }

    /** Include-region names, in the order annotations were tested. */
    public List<String> regionNames() {
        return List.of(regionNames);
    }

    /**
     * The ROI each named region was built from, parallel to {@link #regionNames()} — for a
     * caller that needs a region's area (e.g. {@code roi.getArea()} in pixels²) without
     * re-deriving which annotations fed this mask.
     * <p>
     * An entry is {@code null} exactly for the implicit "whole image, minus exclusions"
     * region: no single ROI represents that shape, so a caller must treat its area as
     * unknown rather than guess one.
     */
    public List<ROI> regionRois() {
        // Not List.of(regionRois): that overload rejects a null element outright (throws
        // eagerly, not merely on access), and this array's implicit-whole-image entry is
        // deliberately null. Collections.unmodifiableList still closes the mutability hole
        // (Arrays.asList(...).set(...) would otherwise corrupt this instance's own array)
        // while tolerating that null the way regionCounts() below already does for its own
        // wrapped array.
        return Collections.unmodifiableList(Arrays.asList(regionRois));
    }

    /**
     * Each region's area in level-0 <b>pixels²</b>, counting exactly the area whose cells
     * this mask assigns to that region — parallel to {@link #regionNames()}.
     * <p>
     * <b>Why not just {@code regionRois().get(i).getArea()}.</b> That is the raw annotation
     * area, and it does not match the cells being counted in it. Two of this class's own
     * rules remove area from a region without removing it from the ROI:
     * <ul>
     *   <li>an {@code Ignore*} exclusion drops every cell inside it, so a tumour annotation
     *       with a necrosis hole counts the cells outside the hole against an area that
     *       still includes it;</li>
     *   <li>include regions are first-match-wins, so where two annotations overlap the
     *       later one's cells are all assigned to the earlier one, while both ROIs still
     *       report the shared area.</li>
     * </ul>
     * Either way the numerator shrinks and the denominator does not, and a density built
     * from the two reads systematically low — worst exactly where a pathologist has been
     * most careful about excluding artefact. Here the same two rules are applied to the
     * geometry: a region is its own ROI, minus every earlier include region, minus every
     * exclusion. The areas therefore partition the annotated area, so they can be summed
     * across regions without double-counting.
     * <p>
     * An entry is {@link Double#NaN} when the area is genuinely unknown — the implicit
     * "whole image minus exclusions" region, which no single ROI describes — or when the
     * geometry could not be resolved. Never {@code 0}: a zero area produces an infinite
     * density, which reads as an answer.
     */
    public double[] effectiveAreasPixels() {
        double[] areas = new double[regionRois.length];

        org.locationtech.jts.geom.Geometry exclusions = null;
        for (ROI roi : excludeRois) {
            try {
                org.locationtech.jts.geom.Geometry g = roi.getGeometry();
                exclusions = exclusions == null ? g : exclusions.union(g);
            } catch (RuntimeException invalidGeometry) {
                // A self-intersecting annotation can make JTS throw. Losing one exclusion
                // would silently inflate every area, so give up on areas entirely rather
                // than report numbers that are quietly wrong.
                Arrays.fill(areas, Double.NaN);
                return areas;
            }
        }

        // The union of the include regions already claimed, in test order. Accumulated from
        // each region's *original* geometry, not its trimmed one: first-match-wins means an
        // earlier region claims the whole of its own ROI.
        org.locationtech.jts.geom.Geometry claimed = null;

        for (int i = 0; i < regionRois.length; i++) {
            ROI roi = regionRois[i];
            if (roi == null) {
                // The implicit whole-image region. Unknown, and it must stay unknown.
                areas[i] = Double.NaN;
                continue;
            }
            try {
                org.locationtech.jts.geom.Geometry own = roi.getGeometry();
                org.locationtech.jts.geom.Geometry effective = own;
                if (claimed != null) effective = effective.difference(claimed);
                if (exclusions != null) effective = effective.difference(exclusions);
                areas[i] = effective.getArea();
                claimed = claimed == null ? own : claimed.union(own);
            } catch (RuntimeException invalidGeometry) {
                areas[i] = Double.NaN;
            }
        }
        return areas;
    }

    /** Cell count per region, parallel to {@link #regionNames()}. */
    public List<Integer> regionCounts() {
        List<Integer> out = new ArrayList<>(regionCounts.length);
        for (int c : regionCounts) out.add(c);
        return Collections.unmodifiableList(out);
    }

    /** Number of annotations skipped because they enclose no area (lines, points). */
    public int droppedNonArea() {
        return droppedNonArea;
    }

    /** Number of annotations acting as subtractions. */
    public int excludeRegionCount() {
        return excludeRegionCount;
    }

    /** Cells that fell inside an include region but were removed by an exclusion. */
    public int excludedByRegion() {
        return excludedByRegion;
    }

    /** Total cells assigned to some region. */
    public int includedCount() {
        int c = 0;
        for (boolean b : included) if (b) c++;
        return c;
    }

    /**
     * {@code true} when no annotation could contribute a region at all, so there is
     * nothing to filter by. Callers should treat this as "no filter" rather than as
     * "exclude everything" — the latter is what made a stray point annotation empty the
     * whole view.
     */
    public boolean isEmpty() {
        return regionNames.length == 0;
    }
}
