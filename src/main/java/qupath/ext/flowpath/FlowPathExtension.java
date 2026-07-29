package qupath.ext.flowpath;

import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import qupath.ext.flowpath.io.PanelModelReader;
import qupath.ext.flowpath.model.PhenotypeTree;
import qupath.ext.flowpath.ui.FlowPathPane;
import qupath.ext.flowpath.ui.PhenotypePane;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.extensions.QuPathExtension;

import java.io.File;
import java.util.Optional;

/**
 * QuPath extension entry point for the interactive tree-based gating tool.
 * Registers a menu item under Extensions and opens a floating Stage.
 */
public class FlowPathExtension implements QuPathExtension {

    private static final String NAME = "FlowPath - GatingTree";
    private static final String PHENOTYPE_NAME = "FlowPath - Phenotyping";
    private static final String DESCRIPTION = "Interactive tree-based cell phenotyping";

    private Stage stage;
    private FlowPathPane gateTreePane;

    private Stage phenotypeStage;

    @Override
    public void installExtension(QuPathGUI qupath) {
        var menuItem = new MenuItem(NAME);
        menuItem.setOnAction(e -> showGateTreeWindow(qupath));
        menuItem.setAccelerator(new KeyCodeCombination(KeyCode.G, KeyCombination.CONTROL_DOWN));
        qupath.getMenu("Extensions", true).getItems().add(menuItem);

        var phenoItem = new MenuItem(PHENOTYPE_NAME);
        phenoItem.setOnAction(e -> showPhenotypeWindow(qupath));
        phenoItem.setAccelerator(new KeyCodeCombination(KeyCode.G,
                KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        qupath.getMenu("Extensions", true).getItems().add(phenoItem);
    }

    private void showGateTreeWindow(QuPathGUI qupath) {
        if (stage != null && stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }

        gateTreePane = new FlowPathPane(qupath);

        stage = new Stage();
        stage.setTitle("FlowPath - GatingTree");
        stage.initOwner(qupath.getStage());
        stage.setScene(new Scene(gateTreePane, 900, 700));
        stage.setMinWidth(700);
        stage.setMinHeight(500);

        stage.setOnCloseRequest(e -> {
            if (gateTreePane != null) {
                gateTreePane.shutdown();
            }
        });

        stage.show();
    }

    private void showPhenotypeWindow(QuPathGUI qupath) {
        if (phenotypeStage != null && phenotypeStage.isShowing()) {
            phenotypeStage.toFront();
            phenotypeStage.requestFocus();
            return;
        }

        var imageData = qupath.getImageData();
        File sidecar = imageData != null
                ? new File(new File(System.getProperty("user.dir")), "panel_model.json")
                : null;
        Optional<PhenotypeTree> maybeTree = PanelModelReader.tryRead(sidecar);
        if (maybeTree.isEmpty()) {
            Dialogs.showWarningNotification(PHENOTYPE_NAME,
                    "No panel_model.json sidecar found — phenotyping unavailable (gating still works).");
            return;
        }

        var phenotypePane = new PhenotypePane(maybeTree.get());

        phenotypeStage = new Stage();
        phenotypeStage.setTitle(PHENOTYPE_NAME);
        phenotypeStage.initOwner(qupath.getStage());
        phenotypeStage.setScene(new Scene(phenotypePane, 1000, 700));
        phenotypeStage.show();
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
