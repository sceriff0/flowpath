package qupath.ext.flowpath;

import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import qupath.ext.flowpath.ui.FlowPathPane;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuPath extension entry point for FlowPath.
 * <p>
 * FlowPath installs a <em>single</em> menu item. Gating is the way in — you phenotype
 * cells with the gate tree, and the UMAP view opens from there, already knowing what
 * your gates mean. It used to ship as two separate extensions with two menu items and
 * two independent copies of the cell index; a user could open the UMAP first, be asked
 * to pick features from forty raw channels, and get an embedding with no relationship to
 * the phenotyping they had spent the afternoon building. One entry point removes that
 * whole class of confusion: there is one place to start and one obvious next step.
 */
public class FlowPathExtension implements QuPathExtension {

    private static final String NAME = "FlowPath";
    // Shown in QuPath's extension manager, so it must describe what this build actually
    // offers. UMAP is present in the source but held back for a future release
    // (FlowPathPane.UMAP_ENABLED); advertising it here would promise a window no user
    // can open. Put it back in this sentence when that flag flips.
    private static final String DESCRIPTION =
            "Interactive tree-based cell phenotyping with hierarchical gates, plus "
                    + "population counts, percentages and density for the resulting "
                    + "phenotypes.";

    private Stage stage;
    private FlowPathPane flowPathPane;

    @Override
    public void installExtension(QuPathGUI qupath) {
        var menuItem = new MenuItem(NAME);
        menuItem.setOnAction(e -> showFlowPathWindow(qupath));
        menuItem.setAccelerator(new KeyCodeCombination(KeyCode.G, KeyCombination.CONTROL_DOWN));
        qupath.getMenu("Extensions", true).getItems().add(menuItem);
    }

    private void showFlowPathWindow(QuPathGUI qupath) {
        if (stage != null && stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }
        // A closed stage leaves its reference behind and cannot be re-shown; drop it
        // and build a fresh one rather than trying to revive a torn-down scene graph.
        stage = null;

        flowPathPane = new FlowPathPane(qupath);

        stage = new Stage();
        stage.setTitle("FlowPath — Gating");
        stage.initOwner(qupath.getStage());
        stage.setScene(new Scene(flowPathPane, 940, 720));
        stage.setMinWidth(700);
        stage.setMinHeight(500);

        stage.setOnCloseRequest(e -> {
            // Closing the gating window closes the UMAP window it owns. The UMAP view
            // holds an entire slide's embedding, and leaving it orphaned behind a closed
            // parent would strand both that memory and a window with no way to refresh.
            if (flowPathPane != null) {
                flowPathPane.shutdown();
            }
            flowPathPane = null;
            stage = null;
        });

        stage.show();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }
}
