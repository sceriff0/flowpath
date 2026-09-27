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
 * live preview. Fires no event; the caller does.
 *
 * <p>{@code PathClass.fromString(name, color)} returns a single cached instance per name, and a
 * repeat call mutates that shared instance's color in place rather than returning a new object.
 * A cell already carrying that class is therefore the *same reference* as the freshly looked-up
 * one even when only its color changed, so a plain {@code Objects.equals} cannot see the
 * change — this is exactly why a colour-by-root switch needs its own check: a class whose color
 * this call actually mutated is tracked separately and forces every cell wearing it to be
 * reassigned (via null then the class, since same-reference {@code setPathClass} is a no-op for
 * QuPath's per-object change tracking) so the recolour is never silently dropped.</p>
 */
public final class PhenotypeClassWriter {

    private PhenotypeClassWriter() {}

    /** @return true when any cell's class (or class colour) changed */
    public static boolean apply(GatingEngine.AssignmentResult result, CellIndex index, int colorRootIndex) {
        String[] phenotypes = result.getPhenotypes();
        boolean[] excluded = result.getExcluded();
        int[] defaultColors = result.getColors();
        List<int[]> perRoot = result.getPerRootColors();
        int n = phenotypes.length;

        Map<String, Integer> colorByName = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (!excluded[i] && phenotypes[i] != null) {
                int color = colorRootIndex >= 0 && perRoot != null && colorRootIndex < perRoot.size()
                        ? perRoot.get(colorRootIndex)[i] : defaultColors[i];
                colorByName.put(phenotypes[i], color);
            }
        }
        Map<String, PathClass> classCache = new HashMap<>();
        Set<String> recolored = new HashSet<>();
        for (var entry : colorByName.entrySet()) {
            int qupathColor = ColorUtils.toQuPathColor(entry.getValue());
            PathClass pc = PathClass.fromString(entry.getKey(), qupathColor);
            if (!Integer.valueOf(qupathColor).equals(pc.getColor())) {
                pc.setColor(qupathColor);
                recolored.add(entry.getKey());
            }
            classCache.put(entry.getKey(), pc);
        }
        // Near-invisible PathClass for excluded cells (avoids red "Unclassified" default)
        int excludedColor = ColorTools.packRGB(20, 20, 20);
        PathClass excludedClass = PathClass.fromString("Excluded", excludedColor);
        excludedClass.setColor(excludedColor);

        boolean changed = false;
        for (int i = 0; i < n; i++) {
            PathObject obj = index.getObject(i);
            if (obj == null) continue;
            PathClass newClass = excluded[i] ? excludedClass : classCache.get(phenotypes[i]);
            boolean recoloredHere = !excluded[i] && phenotypes[i] != null && recolored.contains(phenotypes[i]);
            if (!Objects.equals(obj.getPathClass(), newClass) || recoloredHere) {
                // Same-reference setPathClass is silently ignored by QuPath's per-object
                // change tracking, so a pure recolour goes through null first.
                obj.setPathClass(null);
                obj.setPathClass(newClass);
                changed = true;
            }
        }
        return changed;
    }
}
