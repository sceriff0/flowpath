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
 * Pins the fix for Task 8: {@code GateEditorPane} hard-coded {@code -fx-text-fill: white} in
 * about fifteen places (and dark field backgrounds), {@code FlowPathPane} hard-coded black on
 * its checkboxes, and grey literals were scattered across the rest of {@code ui/}. A literal
 * chosen for one QuPath theme reads fine there and disappears on the other — that is exactly
 * what hid the Quality Filter panel's own labels before {@code ac57d5d} fixed that one spot by
 * hand. This test scans the whole gating half of {@code ui/} -- by directory, so a file added
 * later is covered the day it lands -- and the next hard-coded literal fails a build instead of
 * a user's eyes.
 * <p>
 * A dynamically computed colour — {@code GateEditorPane}'s per-branch label, whose colour
 * comes from the branch's own {@code ColorUtils.intToColor(...)}, not a literal — does not
 * match: the source text right after {@code "-fx-text-fill: "} is a string concatenation, not
 * a colour token, so the regex below does not see one there.
 */
class NoInlineColorLiteralTest {

    /**
     * The whole gating half of the UI: the bare {@code ui} package and both its subpackages.
     * <p>
     * <b>Directories, not a hand-kept file list.</b> This used to enumerate the ten files Task
     * 8 and Task 10 happened to touch, which meant every new UI class started life outside the
     * scan — {@code ui/editor/EditorLabels.java}, added by this very round and one of only two
     * files in {@code ui/editor} that calls {@code setStyle} at all, was never on it. A list
     * that has to be remembered is a list that stops matching the code, and the failure mode is
     * silent: the test stays green while the thing it exists to catch walks in beside it.
     */
    private static final List<Path> DIRECTORIES = List.of(
            Path.of("src/main/java/qupath/ext/flowpath/ui"),
            Path.of("src/main/java/qupath/ext/flowpath/ui/editor"),
            Path.of("src/main/java/qupath/ext/flowpath/ui/widgets"));

    /**
     * Files inside {@link #DIRECTORIES} the rule deliberately does not apply to, by name.
     * Empty today, and that is the intended state: anything added here must carry a comment
     * saying why a literal colour is correct in that file, so an exclusion is a decision on
     * the record rather than an omission nobody notices.
     */
    private static final List<String> EXCLUDED = List.of();

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
        int scanned = 0;
        for (Path path : allScannedFiles()) {
            scanned++;
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = LITERAL.matcher(lines.get(i));
                if (m.find()) {
                    violations.add(path + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Found hard-coded -fx-text-fill/-fx-background-color colour literals "
                        + "(replace with a style class from flowpath.css):\n"
                        + String.join("\n", violations));
        assertTrue(scanned >= 20,
                "the scan found only " + scanned + " source files across " + DIRECTORIES
                        + " -- a directory was renamed or moved and this test is now scanning "
                        + "nothing, which is how a file-list scan fails silently");
    }

    /**
     * Every file a {@code setStyle} call could hide in is actually covered. The old hand-kept
     * list let {@code EditorLabels} in unscanned; this asserts the property that failed, rather
     * than only fixing the one instance of it.
     */
    @Test
    void everyGatingUiFileThatCallsSetStyleIsScanned() throws IOException {
        List<Path> scanned = allScannedFiles();
        List<String> unscanned = new ArrayList<>();
        for (Path dir : DIRECTORIES) {
            for (Path file : javaFilesIn(dir)) {
                if (scanned.contains(file)) continue;
                if (Files.readString(file).contains("setStyle(")) {
                    unscanned.add(file.toString());
                }
            }
        }
        assertTrue(unscanned.isEmpty(),
                "these gating-UI files call setStyle() but are excluded from the colour-literal "
                        + "scan:\n" + String.join("\n", unscanned));
    }

    /** Every {@code .java} file under {@link #DIRECTORIES} that is not {@link #EXCLUDED}. */
    private static List<Path> allScannedFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path dir : DIRECTORIES) {
            for (Path file : javaFilesIn(dir)) {
                if (!EXCLUDED.contains(file.getFileName().toString())) files.add(file);
            }
        }
        return files;
    }

    /** The {@code .java} files directly in {@code dir}, sorted, with the directory pinned present. */
    private static List<Path> javaFilesIn(Path dir) throws IOException {
        assertTrue(Files.isDirectory(dir), "Expected directory to exist: " + dir.toAbsolutePath());
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
