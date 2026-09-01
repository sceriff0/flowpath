package qupath.ext.flowpath.analysis;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.analysis.ui.AnalysisPane;
import qupath.ext.flowpath.analysis.ui.PopulationRef;
import qupath.ext.flowpath.analysis.ui.ScaleOptions;
import qupath.lib.gui.QuPathGUI;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * Owns the single floating Analysis stage and its lifecycle.
 * <p>
 * Mirrors {@code UmapWindow} exactly — see that class. The gating pane holds one of these
 * for its whole life and calls {@link #open} when the user asks for the Analysis window,
 * then {@link #push} whenever the gating changes. Keeping the stage in one place means
 * "open Analysis" is idempotent — a second click focuses the window that is already open
 * rather than stacking a second copy of the same report.
 * <p>
 * <b>Two different lifetimes, two different mechanisms — see {@link AnalysisWindowPrefs}'s
 * own javadoc for the fuller version.</b> A close/reopen within one running session survives
 * for free: {@link #disposeStage} tears down only the {@link Stage}, keeping {@link #pane}
 * alive, so every selection the user made is still sitting there, in memory, the next time
 * {@link #open} runs. {@link AnalysisWindowPrefs}, backed by {@link Preferences}, exists for
 * what in-memory state cannot survive — a JVM restart, and {@link #dispose()} (the genuine
 * teardown {@code FlowPathPane} calls when the extension pane itself goes away) — by seeding
 * the very next {@link AnalysisPane} this window ever builds. Do not read "the window
 * remembers its settings" as "therefore preferences are involved on every close" — that
 * reading is exactly backwards, and it is what would make someone "simplify" this class by
 * deleting the pane-survival change while preferences quietly cover for it, right up until a
 * setting preferences was never taught to carry — the filter text, the table's own selection —
 * regresses with nothing left to catch it.
 * <p>
 * All methods must be called on the JavaFX application thread.
 */
public final class AnalysisWindow {

    private final AnalysisSession session = new AnalysisSession();

    /**
     * Where {@link AnalysisWindowPrefs} is read from and written to. {@link
     * Preferences#userNodeForPackage(Class)} in production — see {@link #AnalysisWindow()} —
     * but overridable by the package-private constructor below so {@code AnalysisWindowFxTest}
     * can point a real, fully-functional {@code AnalysisWindow} at a scratch node instead of
     * the developer's or CI machine's actual saved settings. Without this seam, any test that
     * exercises real open()/close() geometry persistence would read and overwrite whatever this
     * machine has genuinely saved — exactly the "must not pollute the real preferences tree"
     * rule {@code AnalysisWindowPrefsTest} already follows for the record's own tests.
     */
    private final Preferences prefsNode;

    private Stage stage;
    private AnalysisPane pane;

    /** Production constructor: preferences live under {@link Preferences#userNodeForPackage}. */
    public AnalysisWindow() {
        this(Preferences.userNodeForPackage(AnalysisWindow.class));
    }

    /** Package-private: lets a test supply a scratch node — see {@link #prefsNode}. */
    AnalysisWindow(Preferences prefsNode) {
        this.prefsNode = Objects.requireNonNull(prefsNode, "prefsNode");
    }

    /**
     * The host's callback for Task 14's forward direction — a population picked inside the
     * Analysis pane (a table row, or a plot bar click). Held here, not only handed to
     * {@link AnalysisPane}, because the pane itself is rebuilt by every {@link #open} call
     * after a close (see {@link #disposeStage}); a handler installed only on the pane that
     * happened to exist when {@code setPopulationSelectionListener} was called would silently
     * stop firing the moment the window was closed and reopened.
     */
    private Consumer<PopulationRef> populationSelectionListener;

    /**
     * Show the Analysis view for a gating pass, creating the window on first use and
     * focusing it thereafter. Re-opening with a newer pass refreshes the existing window
     * in place.
     *
     * @param qupath the QuPath GUI, used as the stage owner
     * @param input  the current gating pass; must not be {@code null}
     * @param owner  window to centre on, or {@code null} to centre on QuPath
     */
    public void open(QuPathGUI qupath, AnalysisSession.AnalysisInput input, Window owner) {
        if (stage != null && stage.isShowing()) {
            pane.accept(input);
            stage.setTitle(titleFor(input));
            stage.toFront();
            stage.requestFocus();
            return;
        }
        // A stage that was closed leaves its reference behind; drop it rather than trying to
        // re-show it, because its scene graph has already been torn down -- but disposeStage()
        // deliberately leaves `pane` standing (see this class's own javadoc), so the very same
        // AnalysisPane instance -- with every selection the user made still on it -- is what
        // the fresh Scene built below gets re-parented onto. This is NOT automatic: verified
        // empirically (AnalysisWindowFxTest.theSamePaneReparentsIntoAFreshSceneAcrossAClose)
        // that Stage.close() alone does NOT release a Scene's root -- `new Scene(pane, ...)`
        // here threw "AnalysisPane is already set as root of another scene" until
        // disposeStage() itself was made to explicitly evict `pane` from the old Scene first.
        // See that method's own javadoc for the fix.
        disposeStage();

        AnalysisWindowPrefs prefs = AnalysisWindowPrefs.load(prefsNode)
                .clampToScreen(Screen.getPrimary().getVisualBounds());

        if (pane == null) {
            // Only reached once per AnalysisWindow instance in practice -- the very first
            // open() after this window (or a freshly built replacement, post-dispose()) came
            // into existence -- which is exactly when there is no live in-memory state to fall
            // back on and AnalysisWindowPrefs is the only source left. Every later open() in
            // this same session skips this branch entirely; see this class's own javadoc.
            pane = new AnalysisPane(session, prefs.selectedTab(), prefs.scope(),
                    new ScaleOptions(prefs.log(), prefs.clip(), prefs.percentile()));
            pane.setOnPopulationSelected(populationSelectionListener);
        }
        stage = new Stage();
        stage.setTitle(titleFor(input));
        stage.initOwner(owner != null ? owner : qupath.getStage());
        stage.setScene(new Scene(pane, prefs.width(), prefs.height()));
        stage.setMinWidth(720);
        stage.setMinHeight(480);
        // NaN means "never saved" -- see AnalysisWindowPrefs.defaults() -- and is left alone so
        // a genuinely first-ever open gets the platform's own default placement rather than a
        // hard-coded corner.
        if (!Double.isNaN(prefs.x())) stage.setX(prefs.x());
        if (!Double.isNaN(prefs.y())) stage.setY(prefs.y());
        stage.setOnCloseRequest(e -> {
            saveWindowPrefs();
            disposeStage();
        });

        pane.accept(input);
        stage.show();
    }

    /**
     * Push an updated gating pass into an already-open window; a no-op when closed.
     * <p>
     * Deliberately does not open the window. The gating pane recomputes on every gate
     * edit, and an Analysis window that sprang open on its own the first time a gate moved
     * would be an ambush rather than a feature.
     * <p>
     * Also re-sets the title, not only the pane's content — a live-preview push can move a
     * report from one image to another (the gating pane holds one {@code AnalysisWindow} for
     * its whole life, across images), and a title set once at {@link #open} would keep
     * naming the image the window was first opened on.
     */
    public void push(AnalysisSession.AnalysisInput input) {
        if (stage != null && stage.isShowing() && pane != null) {
            pane.accept(input);
            stage.setTitle(titleFor(input));
        }
    }

    /**
     * {@code "FlowPath — Analysis — " + imageName} when the pass names an image, the plain
     * title otherwise — a QuPath image can genuinely have no name, so a blank/{@code null}
     * name falls back rather than appending an empty suffix.
     */
    private static String titleFor(AnalysisSession.AnalysisInput input) {
        String imageName = input == null ? null : input.imageName();
        return imageName == null || imageName.isBlank()
                ? "FlowPath — Analysis"
                : "FlowPath — Analysis — " + imageName;
    }

    /**
     * Install the host's callback for Task 14's forward direction: a population picked inside
     * the Analysis pane (a table row selection, or a plot bar click) is reported here. Wired
     * onto the pane immediately if one already exists (the window is open), and onto every
     * pane {@link #open} goes on to build afterward — see {@link #populationSelectionListener}'s
     * own javadoc for why the field, not just the pane, is what this method sets.
     *
     * @param handler called with the population's {@code (rootIndex, path)}; {@code
     *                 FlowPathPane} is the one production caller, resolving it against the
     *                 live gate tree via {@code GateTree.findBranch} and selecting the
     *                 matching tree item
     */
    public void setPopulationSelectionListener(Consumer<PopulationRef> handler) {
        this.populationSelectionListener = handler;
        if (pane != null) {
            pane.setOnPopulationSelected(handler);
        }
    }

    /**
     * Test seam: the live pane, or {@code null} before the first {@link #open}. Used by
     * {@code AnalysisWindowFxTest} to confirm the SAME {@link AnalysisPane} instance — with
     * whatever state a real user interaction left it in — survives a close and a subsequent
     * re-open, which is the actual claim behind this class's own "the pane survives a close"
     * javadoc; nothing else exposes the field.
     */
    AnalysisPane paneForTest() {
        return pane;
    }

    /**
     * Land the Analysis pane's own selection (table row plus both comparison plots) on {@code
     * ref} — Task 14's reverse direction, driven by the gate tree's own selection changing. A
     * no-op while the window is closed: there is no pane to apply it to, and re-opening later
     * does not replay a selection that arrived while nothing was open, the same way {@link
     * #push} does not open the window on its own.
     */
    public void selectPopulation(PopulationRef ref) {
        if (stage != null && stage.isShowing() && pane != null) {
            pane.selectPopulation(ref);
        }
    }

    /** {@code true} when the Analysis window is currently on screen. */
    public boolean isShowing() {
        return stage != null && stage.isShowing();
    }

    /**
     * Hide the window for the rest of this session. Safe to call when already closed.
     * <p>
     * <b>Deliberately does not release {@link #pane}.</b> That is the whole point of this
     * task: closing the window used to discard the panel along with the stage, so every
     * selection reset on the next {@link #open}. Genuine teardown — the point {@code
     * FlowPathPane} calls when the extension pane itself is going away — is {@link #dispose()}
     * instead; see that method and this class's own javadoc for why the two are not the same
     * operation.
     */
    public void close() {
        if (stage != null) {
            saveWindowPrefs();
            stage.close();
        }
        disposeStage();
    }

    /**
     * Release the {@link Stage} only. {@link #pane} survives — see this class's own javadoc —
     * so the very same {@link AnalysisPane}, with every selection the user made still on it,
     * is what the next {@link #open} re-parents into a freshly built {@link Scene}, rather than
     * a brand-new pane starting from nothing.
     * <p>
     * <b>{@link Stage#close()} does not, by itself, make that re-parenting possible.</b> This
     * was checked empirically rather than assumed — see
     * {@code AnalysisWindowFxTest.theSamePaneReparentsIntoAFreshSceneAcrossAClose} — and a
     * closed {@code Stage}'s {@link Scene} keeps {@link #pane} as its root regardless: handing
     * {@code pane} to {@code new Scene(pane, ...)} on the next {@link #open} threw {@code
     * IllegalArgumentException: ... is already set as root of another scene}. Swapping the old
     * {@code Scene}'s root for a throwaway, never-shown {@link javafx.scene.layout.Pane} is
     * what actually evicts {@code pane} from it — {@link Scene} requires a non-null root at all
     * times, so there is no "clear the root" call that does not involve installing something
     * else in {@code pane}'s place.
     */
    private void disposeStage() {
        if (stage != null) {
            Scene oldScene = stage.getScene();
            if (oldScene != null) {
                oldScene.setRoot(new Pane());
            }
        }
        stage = null;
    }

    /**
     * True teardown: releases {@link #pane} along with the {@link Stage}. {@code FlowPathPane}
     * calls this from {@code shutdown()} — the extension builds an entirely new
     * {@code FlowPathPane} (and a new {@code AnalysisWindow} inside it) the next time its own
     * window is reopened, so there is no future {@link #open} on THIS instance left to benefit
     * from keeping {@link #pane} around; holding onto it past this point would only be a leak.
     * <p>
     * Saves first, exactly like {@link #close()} — the tab, scope and scale settings the
     * user had open are what the NEXT {@code AnalysisWindow}, built fresh after this one, seeds
     * its own first {@link AnalysisPane} from via {@link AnalysisWindowPrefs}. Safe to call
     * repeatedly, or on a window that was never opened at all.
     */
    public void dispose() {
        if (stage != null) {
            saveWindowPrefs();
            stage.close();
        }
        stage = null;
        pane = null;
    }

    /**
     * Persist the current stage geometry and the pane's own tab/scope/scale — a no-op with
     * nothing open to read them from. Called from both {@link #close()}'s explicit path and
     * {@code setOnCloseRequest} in {@link #open}, since a user clicking the window's own close
     * button never goes through {@link #close()} at all.
     */
    private void saveWindowPrefs() {
        if (stage == null || pane == null) return;
        String scope = pane.selectedScopeName();
        ScaleOptions scale = pane.currentScaleOptions();
        new AnalysisWindowPrefs(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(),
                pane.selectedTabIndex(),
                scope == null ? AnalysisWindowPrefs.defaults().scope() : scope,
                scale.log(), scale.clip(), scale.percentile())
                .save(prefsNode);
    }
}
