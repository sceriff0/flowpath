package qupath.ext.flowpath.umap.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Non-modal floating progress dialog for UMAP computation.
 * Shows a progress bar, current stage, and elapsed time.
 * Provides a Cancel button for user control.
 */
class UmapProgressDialog {

    private final Stage stage;
    private final ProgressBar progressBar;
    private final Label stageLabel;
    private final Label elapsedLabel;
    private final Button cancelButton;
    private final Timeline timer;
    private long startTime;
    private Runnable onCancel;

    // Known computation stages and their approximate weight (cumulative 0.0 - 1.0)
    private static final String[] STAGE_KEYWORDS = {
            "Subsampling", "Preparing", "Building neighbor", "Building spatial",
            "Optimizing", "Projecting"
    };
    private static final double[] STAGE_PROGRESS = {
            0.02, 0.05, 0.15, 0.20, 0.75, 0.80
    };

    UmapProgressDialog(Window owner) {
        stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Computing UMAP...");
        stage.initOwner(owner);
        stage.setResizable(false);
        // Closing the dialog window also cancels computation
        stage.setOnCloseRequest(e -> {
            if (onCancel != null) onCancel.run();
        });

        stageLabel = new Label("Initializing...");
        stageLabel.setStyle("-fx-font-size: 13; -fx-text-fill: #e0e0e0;");
        stageLabel.setMaxWidth(350);
        stageLabel.setWrapText(true);

        progressBar = new ProgressBar(-1); // indeterminate initially
        progressBar.setPrefWidth(350);
        progressBar.setPrefHeight(22);

        elapsedLabel = new Label("0s");
        elapsedLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #999;");

        cancelButton = new Button("Cancel");
        cancelButton.setStyle("-fx-background-color: #555; -fx-text-fill: white;");
        cancelButton.setOnAction(e -> {
            if (onCancel != null) onCancel.run();
        });

        var bottomRow = new HBox(12, elapsedLabel, cancelButton);
        bottomRow.setAlignment(Pos.CENTER_RIGHT);

        var root = new VBox(10, stageLabel, progressBar, bottomRow);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: #2a2a2a;");
        root.setAlignment(Pos.CENTER_LEFT);

        var scene = new Scene(root, 390, 110);
        scene.setFill(null);
        stage.setScene(scene);

        // Elapsed time ticker
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateElapsed()));
        timer.setCycleCount(Animation.INDEFINITE);
    }

    void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    void show() {
        startTime = System.currentTimeMillis();
        progressBar.setProgress(-1);
        stageLabel.setText("Initializing...");
        elapsedLabel.setText("0s");
        stage.show();
        timer.play();
    }

    void updateStatus(String message) {
        stageLabel.setText(message);

        // Estimate progress from known stage keywords
        for (int i = STAGE_KEYWORDS.length - 1; i >= 0; i--) {
            if (message.contains(STAGE_KEYWORDS[i])) {
                // Check for percentage in projection stage
                if (message.contains("%")) {
                    try {
                        int pctStart = message.lastIndexOf(' ', message.indexOf('%') - 1) + 1;
                        int pct = Integer.parseInt(message.substring(pctStart, message.indexOf('%')).trim());
                        // Projection goes from 0.80 to 1.0
                        double projBase = STAGE_PROGRESS[STAGE_PROGRESS.length - 1];
                        progressBar.setProgress(projBase + (1.0 - projBase) * pct / 100.0);
                    } catch (NumberFormatException ignored) {
                        progressBar.setProgress(STAGE_PROGRESS[i]);
                    }
                } else {
                    progressBar.setProgress(STAGE_PROGRESS[i]);
                }
                return;
            }
        }
    }

    void close() {
        timer.stop();
        stage.close();
    }

    private void updateElapsed() {
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        if (elapsed < 60) {
            elapsedLabel.setText(elapsed + "s");
        } else {
            elapsedLabel.setText("%dm %ds".formatted(elapsed / 60, elapsed % 60));
        }
    }
}
