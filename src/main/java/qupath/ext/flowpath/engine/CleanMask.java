package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.RegionMask;
import qupath.ext.flowpath.model.RoundMask;
import qupath.lib.geom.Point2;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which of a slide's cells are <em>clean</em>: the quality filter, then the ROI filter, combined.
 * The one composition of the two — the open slide's resync ({@code ui/GatingSession.derive}), a
 * batch run's slide ({@code batch/BatchRunner}) and every cohort sample
 * ({@code cohort/SlideSample.scopedTo}) all build their mask here, so the population a landmark,
 * a review flag or a marker rule is judged on is the one the live pass counts as clean.
 * <p>
 * An ROI filter whose annotations enclose no area filters nothing ({@link #regions()} null)
 * rather than excluding every cell: the only annotation on a slide being a point or a line
 * otherwise emptied the whole view.
 *
 * @param regions  which annotated region each cell fell in; null when the ROI filter is off or
 *                 nothing usable was annotated
 * @param quality  the quality filter's mask; null when the tree has no filter
 * @param combined the two combined; null when neither filters anything
 * @param rounds   the round-QC failures under the same filter: not a cell mask (a failed round
 *                 removes that round's markers, not the cell), carried here so the one place the
 *                 filter is applied applies all of it; never null
 */
public record CleanMask(RegionMask regions, boolean[] quality, boolean[] combined, RoundMask rounds) {

    public static final CleanMask NONE = new CleanMask(null, null, null, RoundMask.NONE);

    public CleanMask {
        rounds = rounds == null ? RoundMask.NONE : rounds;
    }

    public static CleanMask of(CellIndex index, QualityFilter filter, boolean roiFilterEnabled,
                               List<PathObject> annotations) {
        RegionMask regions = roiFilterEnabled ? usableRegions(index, annotations) : null;
        boolean[] roi = regions == null ? null : regions.included();
        boolean[] quality = filter == null ? null : GatingEngine.computeQualityMask(index, filter);
        boolean[] combined = quality == null ? roi
                : roi == null ? quality
                : GatingEngine.combineMasks(quality, roi);
        RoundMask rounds = filter == null ? RoundMask.NONE : index.roundQc().mask(filter);
        return new CleanMask(regions, quality, combined, rounds);
    }

    /** The ROI filter's inclusion mask, or null when it filters nothing. */
    public boolean[] roi() {
        return regions == null ? null : regions.included();
    }

    /** {@link #combined()}, or every one of {@code n} cells when nothing filters. */
    public boolean[] cleanOrAll(int n) {
        if (combined != null) return combined;
        boolean[] all = new boolean[n];
        Arrays.fill(all, true);
        return all;
    }

    private static RegionMask usableRegions(CellIndex index, List<PathObject> annotations) {
        RegionMask computed = RegionMask.compute(index, annotations == null ? List.of() : annotations);
        return computed.isEmpty() ? null : computed;
    }

    /**
     * A digest of what {@link #of} reads besides the cells: every quality-filter range (by slug,
     * exact bits), the ROI flag and — only while it is on — each annotation's shape and class, in
     * order (the order decides which region a cell falls in). Equal digests over the same cells
     * give the same mask; the cohort keys its cached landmarks on it, so a filter or ROI change
     * re-derives them and nothing else does.
     */
    public static String inputsDigest(QualityFilter filter, boolean roiFilterEnabled, List<PathObject> annotations) {
        MessageDigest digest = sha256();
        StringBuilder text = new StringBuilder();
        Map<String, QualityFilter.Range> ranges = new TreeMap<>(filter == null ? Map.of() : filter.ranges());
        text.append(filter == null ? "no-filter" : "filter").append('\n');
        ranges.forEach((slug, r) -> text.append(slug).append('=')
                .append(Long.toHexString(Double.doubleToLongBits(r.min()))).append(',')
                .append(Long.toHexString(Double.doubleToLongBits(r.max()))).append('\n'));
        text.append("roi=").append(roiFilterEnabled).append('\n');
        digest.update(text.toString().getBytes(StandardCharsets.UTF_8));
        if (roiFilterEnabled && annotations != null) {
            ByteBuffer buf = ByteBuffer.allocate(16);
            for (PathObject ann : annotations) {
                ROI roi = ann == null ? null : ann.getROI();
                String kind = roi == null ? "none" : roi.getRoiName() + (roi.isArea() ? "/area" : "");
                String pathClass = ann == null || ann.getPathClass() == null ? "" : ann.getPathClass().toString();
                digest.update((kind + "|" + pathClass + "\n").getBytes(StandardCharsets.UTF_8));
                if (roi == null) continue;
                for (Point2 p : roi.getAllPoints()) {
                    buf.clear();
                    buf.putDouble(p.getX()).putDouble(p.getY());
                    digest.update(buf.array());
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest(), 0, 16);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }
}
