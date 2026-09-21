package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fix for Task 3: {@code ui.editor} used to import {@code ui.SliderUtils},
 * {@code ui.HistogramCanvas} and {@code ui.ScatterPlotCanvas} while {@code ui.GateEditorPane}
 * imported the editors — a package cycle. The three leaf widgets now live in
 * {@code ui.widgets}, which depends on neither {@code ui} nor {@code ui.editor}, so the
 * dependency graph is one-way: {@code ui} may depend on {@code ui.editor} and on
 * {@code ui.widgets}, {@code ui.editor} may depend on {@code ui.widgets}, and
 * {@code ui.widgets} depends on neither.
 * <p>
 * This test scans {@code src/main} source text for the two edges that would reintroduce a
 * cycle: a direct class in {@code ui} imported from {@code ui.editor} or from
 * {@code ui.widgets}, and {@code ui.editor} imported from {@code ui.widgets}. It does not use a
 * bytecode/AST dependency-analysis library — none is a project dependency — so, like
 * {@code NoInlineColorLiteralTest}, it is a source-text scan, not a compiler-level guarantee.
 */
class UiPackageDependencyDirectionTest {

    private static final Path UI_DIR = Path.of("src/main/java/qupath/ext/flowpath/ui");
    private static final Path EDITOR_DIR = UI_DIR.resolve("editor");
    private static final Path WIDGETS_DIR = UI_DIR.resolve("widgets");

    /** A direct class in the bare {@code ui} package — not {@code ui.editor} or {@code ui.widgets}. */
    private static final Pattern IMPORTS_BARE_UI = Pattern.compile(
            "^import qupath\\.ext\\.flowpath\\.ui\\.[A-Z][A-Za-z0-9_]*;\\s*$");

    private static final Pattern IMPORTS_UI_EDITOR = Pattern.compile(
            "^import qupath\\.ext\\.flowpath\\.ui\\.editor\\.");

    private static final Pattern IMPORTS_UI_WIDGETS = Pattern.compile(
            "^import qupath\\.ext\\.flowpath\\.ui\\.widgets\\.");

    @Test
    void editorNeverImportsBackFromUi() throws IOException {
        assertNoImportMatching(EDITOR_DIR, IMPORTS_BARE_UI,
                "ui.editor must not import a class from the bare ui package "
                        + "(that would recreate the ui <-> ui.editor cycle)");
    }

    @Test
    void widgetsIsALeafDependingOnNeitherUiNorEditor() throws IOException {
        assertNoImportMatching(WIDGETS_DIR, IMPORTS_BARE_UI,
                "ui.widgets must not import a class from the bare ui package");
        assertNoImportMatching(WIDGETS_DIR, IMPORTS_UI_EDITOR,
                "ui.widgets must not import from ui.editor");
    }

    /**
     * The allowed direction is asserted, not merely described: {@code ui} really does import
     * from both {@code ui.editor} and {@code ui.widgets}, so the two tests above are forbidding
     * the reverse of an edge that exists rather than passing vacuously over a graph with no
     * edges at all. Delete a subpackage's last consumer and this fails, which is the point —
     * a one-way rule over a dependency nobody has any more is not a rule worth keeping.
     * <p>
     * This used to assert {@code Files.exists("QualityFilterPane.java")}, which could not fail
     * for the reason its name gives.
     */
    @Test
    void uiIsAllowedToDependOnWidgetsAndEditor() throws IOException {
        assertTrue(importsAnywhereIn(UI_DIR, IMPORTS_UI_EDITOR),
                "no class in the bare ui package imports from ui.editor any more, so "
                        + "editorNeverImportsBackFromUi is guarding an edge that no longer exists");
        assertTrue(importsAnywhereIn(UI_DIR, IMPORTS_UI_WIDGETS),
                "no class in the bare ui package imports from ui.widgets any more, so "
                        + "widgetsIsALeafDependingOnNeitherUiNorEditor is guarding an edge that "
                        + "no longer exists");
    }

    /** Whether any {@code .java} file directly in {@code dir} has an import matching {@code pattern}. */
    private static boolean importsAnywhereIn(Path dir, Pattern pattern) throws IOException {
        assertTrue(Files.isDirectory(dir), "Expected directory to exist: " + dir.toAbsolutePath());
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (pattern.matcher(line.trim()).find()) return true;
                }
            }
        }
        return false;
    }

    private static void assertNoImportMatching(Path dir, Pattern pattern, String message) throws IOException {
        List<String> violations = new ArrayList<>();
        assertTrue(Files.isDirectory(dir), "Expected directory to exist: " + dir.toAbsolutePath());
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = pattern.matcher(lines.get(i).trim());
                    if (m.find()) {
                        violations.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), message + ":\n" + String.join("\n", violations));
    }
}
