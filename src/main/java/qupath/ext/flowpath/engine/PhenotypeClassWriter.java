package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.ColorUtils;
import qupath.lib.common.ColorTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The one place a gating result becomes {@code PathClass}es on detections — the live pass, the
 * colour-by-root recolour and the batch write-back all call it, so a cell classified on one
 * slide by the batch, or recoloured in the viewer, reads exactly as it would have under the
 * live preview. Fires no event; the boolean this returns is what tells the caller whether one
 * is worth firing.
 *
 * <p>{@code PathClass.fromString(name, color)} caches a single instance per name for the whole
 * JVM, and a repeat call mutates that shared instance's colour in place rather than returning a
 * new object. {@link #apply(Map, GatingEngine.AssignmentResult, CellIndex)} therefore compares
 * each class's colour to whatever it holds <em>right now</em>, before mutating it, and reports a
 * change when that differs. That "right now" is JVM-wide state, though, and any other caller —
 * a background batch run gating a different slide that happens to share a phenotype name with
 * this one — can already have written the exact colour this call was about to write. This call
 * then, correctly but misleadingly, reports "nothing changed", even though the caller's own view
 * was never told to refresh and is still showing whatever it painted last. A caller that needs
 * that guarantee — {@code LivePreviewService} is the one that does — keeps its own name→colour
 * map of what it last actually applied and compares a fresh {@link #colorPlan} against
 * <em>that</em> instead of trusting this method's return value alone.</p>
 */
public final class PhenotypeClassWriter {

    private PhenotypeClassWriter() {}

    /** Excluded cells always get this colour; it never varies with the root, so it is not
     *  part of {@link #colorPlan}. */
    private static final int EXCLUDED_COLOR = ColorTools.packRGB(20, 20, 20);

    /**
     * The colour {@code colorRootIndex} paints each phenotype, for non-excluded cells. Pure —
     * touches no {@code PathClass}. Exposed so a caller can diff two plans against each other,
     * or against its own memory of what it last applied (see the class javadoc), without
     * re-deriving this mapping itself.
     */
    static Map<String, Integer> colorPlan(GatingEngine.AssignmentResult result, int colorRootIndex) {
        String[] phenotypes = result.getPhenotypes();
        boolean[] excluded = result.getExcluded();
        int[] defaultColors = result.getColors();
        List<int[]> perRoot = result.getPerRootColors();
        Map<String, Integer> colorByName = new HashMap<>();
        for (int i = 0; i < phenotypes.length; i++) {
            if (!excluded[i] && phenotypes[i] != null) {
                int color = colorRootIndex >= 0 && perRoot != null && colorRootIndex < perRoot.size()
                        ? perRoot.get(colorRootIndex)[i] : defaultColors[i];
                colorByName.put(phenotypes[i], color);
            }
        }
        return colorByName;
    }

    /**
     * One-shot: compute {@link #colorPlan} for {@code colorRootIndex} and apply it. For a caller
     * with no "what did I last show" of its own to compare against — the batch write-back writes
     * each slide's file exactly once, so there is nothing to diff against.
     *
     * @return true when any cell's class, or a class's colour, changed
     */
    public static boolean apply(GatingEngine.AssignmentResult result, CellIndex index, int colorRootIndex) {
        return apply(colorPlan(result, colorRootIndex), result, index);
    }

    /**
     * Apply a precomputed {@link #colorPlan}. A caller that keeps its own applied-colour memory
     * should use {@link #colorPlan} plus its own before/after comparison to decide whether to
     * refresh, rather than rely solely on the boolean this returns — see the class javadoc.
     *
     * @return true when any cell's class, or a class's colour (as observed against the shared
     *         {@code PathClass} cache), changed
     */
    static boolean apply(Map<String, Integer> colorPlan, GatingEngine.AssignmentResult result, CellIndex index) {
        String[] phenotypes = result.getPhenotypes();
        boolean[] excluded = result.getExcluded();
        int n = phenotypes.length;

        Map<String, PathClass> classCache = new HashMap<>();
        Set<String> recolored = new HashSet<>();
        for (var entry : colorPlan.entrySet()) {
            int qupathColor = ColorUtils.toQuPathColor(entry.getValue());
            PathClass pc = PathClass.fromString(entry.getKey(), qupathColor);
            if (!Integer.valueOf(qupathColor).equals(pc.getColor())) {
                pc.setColor(qupathColor);
                recolored.add(entry.getKey());
            }
            classCache.put(entry.getKey(), pc);
        }

        // Near-invisible PathClass for excluded cells (avoids red "Unclassified" default),
        // tracked the same way as every other class so an external reset also counts as a change.
        PathClass excludedClass = PathClass.fromString("Excluded", EXCLUDED_COLOR);
        boolean excludedRecolored = !Integer.valueOf(EXCLUDED_COLOR).equals(excludedClass.getColor());
        if (excludedRecolored) excludedClass.setColor(EXCLUDED_COLOR);

        boolean changed = false;
        for (int i = 0; i < n; i++) {
            PathObject obj = index.getObject(i);
            if (obj == null) continue;
            PathClass newClass = excluded[i] ? excludedClass : classCache.get(phenotypes[i]);
            boolean recoloredHere = excluded[i] ? excludedRecolored
                    : phenotypes[i] != null && recolored.contains(phenotypes[i]);
            boolean identityChanged = !Objects.equals(obj.getPathClass(), newClass);
            if (identityChanged) {
                // QuPath's redraw is driven by the caller firing a hierarchy-changed event, not
                // by any identity trick on the PathObject itself, so a plain reassignment suffices.
                obj.setPathClass(newClass);
            }
            if (identityChanged || recoloredHere) {
                changed = true;
            }
        }
        return changed;
    }
}
