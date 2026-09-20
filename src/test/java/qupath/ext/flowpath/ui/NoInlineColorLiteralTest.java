package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fix for Task 8: {@code GateEditorPane} hard-coded {@code -fx-text-fill: white} in
 * about fifteen places (and dark field backgrounds), {@code FlowPathPane} hard-coded black on
 * its checkboxes, and grey literals were scattered across the rest of {@code ui/}. A literal
 * chosen for one QuPath theme reads fine there and disappears on the other — that is exactly
 * what hid the Quality Filter panel's own labels before {@code ac57d5d} fixed that one spot by
 * hand. This test scans the whole gating half of {@code ui/} so the next hard-coded literal
 * fails a build instead of a user's eyes.
 * <p>
 * A dynamically computed colour — {@code GateEditorPane}'s per-branch label, whose colour
 * comes from the branch's own {@code ColorUtils.intToColor(...)}, not a literal — does not
 * match: the source text right after {@code "-fx-text-fill: "} is a string concatenation, not
 * a colour token, so the regex below does not see one there.
 */
class NoInlineColorLiteralTest {

    /**
     * The gating-half files this task named: {@code FlowPathPane}, {@code GateEditorPane},
     * {@code QualityFilterPane} and {@code FlowPathCell}, plus the {@code ui/editor} type
     * editors that took over {@code GateEditorPane}'s gate-specific controls. Canvas drawing
     * ({@code ScatterPlotCanvas}, {@code HistogramCanvas}) and the UMAP/analysis halves are
     * out of scope for this task.
     */
    private static final List<String> FILES = List.of(
            "src/main/java/qupath/ext/flowpath/ui/FlowPathPane.java",
            "src/main/java/qupath/ext/flowpath/ui/GateEditorPane.java",
            "src/main/java/qupath/ext/flowpath/ui/QualityFilterPane.java",
            "src/main/java/qupath/ext/flowpath/ui/FlowPathCell.java",
            // The tree view's drag-and-drop state (Task 4): its hover cue is a style class,
            // and this file is on the list so it stays one.
            "src/main/java/qupath/ext/flowpath/ui/GateDragCoordinator.java",
            // The per-gate-type controls GateEditorPane was split into (Task 10).
            "src/main/java/qupath/ext/flowpath/ui/editor/AbstractGateTypeEditor.java",
            "src/main/java/qupath/ext/flowpath/ui/editor/TwoAxisGateEditor.java",
            "src/main/java/qupath/ext/flowpath/ui/editor/ThresholdGateEditor.java",
            "src/main/java/qupath/ext/flowpath/ui/editor/QuadrantGateEditor.java",
            "src/main/java/qupath/ext/flowpath/ui/editor/Region2DGateEditor.java"
    );

    /**
     * Matches a CSS colour literal immediately following {@code -fx-text-fill:} or
     * {@code -fx-background-color:} in the raw source text: a named colour, a {@code #hex}
     * value, or an {@code rgb(}/{@code rgba(} call. A looked-up token
     * ({@code -fx-text-base-color}), a {@code derive(...)}/{@code ladder(...)} expression, or
     * a runtime string concatenation all start with something other than one of those, so
     * none of them match.
     */
    private static final Pattern LITERAL = Pattern.compile(
            "-fx-(?:text-fill|background-color)\\s*:\\s*"
                    + "(white|black|red|green|blue|yellow|gray|grey|orange|purple|pink|cyan|magenta"
                    + "|#[0-9a-fA-F]{3,8}\\b|rgb\\(|rgba\\()");

    @Test
    void noHardCodedColorLiteralsRemainInGatingUi() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String file : FILES) {
            Path path = Path.of(file);
            assertTrue(Files.exists(path), "Expected file to exist: " + path.toAbsolutePath());
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = LITERAL.matcher(lines.get(i));
                if (m.find()) {
                    violations.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Found hard-coded -fx-text-fill/-fx-background-color colour literals "
                        + "(replace with a style class from flowpath.css):\n"
                        + String.join("\n", violations));
    }
}
