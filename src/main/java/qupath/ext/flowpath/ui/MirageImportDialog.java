package qupath.ext.flowpath.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import qupath.ext.flowpath.mirage.MiragePrefs;
import qupath.ext.flowpath.mirage.MirageRun;
import qupath.ext.flowpath.mirage.ProjectBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * "New project from MIRAGE…": pick the run's output folder, the cells file, and the project
 * folder, and see every patient found — and what will happen to it — before anything is written.
 * <p>
 * A Humble Object: the scan is {@link MirageRun#scan}, the names already in the target come from
 * {@link ProjectBuilder#existingImageNames}, and both are table-tested; this class only lays them
 * out and re-asks them whenever a choice changes. Colours come from the {@code fp-*} classes of
 * the stylesheet it attaches, so the dialog follows QuPath's light or dark theme.
 */
final class MirageImportDialog {

    /** What the user confirmed. {@code skipped} counts the patients the preview showed but will not add. */
    record Request(Path projectDir, List<MirageRun.Patient> ready, int skipped) {}

    private final Preferences prefs = MiragePrefs.node();
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final TextField folderField = new TextField();
    private final ChoiceBox<MirageRun.Geometry> geometryBox =
            new ChoiceBox<>(FXCollections.observableArrayList(MirageRun.Geometry.values()));
    private final TextField projectField = new TextField();
    private final TableView<MirageRun.Patient> table = new TableView<>();
    private final Label summary = new Label();
    private final ButtonType importType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);

    /** The last scan, or {@code null} when the folder could not be scanned. */
    private MirageRun run;
    /** The project folder was typed or browsed by the user: stop replacing it with the suggestion. */
    private boolean projectChosen;

    private MirageImportDialog(Window owner) {
        dialog.initOwner(owner);
        dialog.setTitle("New project from MIRAGE");
        dialog.setHeaderText("Create a QuPath project from a MIRAGE output folder: one image per patient,\n"
                + "its cells imported and saved, ready for gating many slides.");
        dialog.setResizable(true);
        dialog.getDialogPane().getStylesheets().add(
                MirageImportDialog.class.getResource("/qupath/ext/flowpath/ui/flowpath.css").toExternalForm());
        dialog.getDialogPane().getButtonTypes().addAll(importType, ButtonType.CANCEL);

        folderField.setEditable(false);
        folderField.setPromptText("The MIRAGE --outdir (holds one folder per patient)");
        projectField.setPromptText("Where the QuPath project goes");
        Button browseFolder = new Button("Browse…");
        browseFolder.setOnAction(e -> chooseFolder());
        Button browseProject = new Button("Browse…");
        browseProject.setOnAction(e -> chooseProject());

        geometryBox.setValue(MiragePrefs.geometry(prefs));
        geometryBox.setOnAction(e -> {
            MiragePrefs.setGeometry(prefs, geometryBox.getValue());
            rescan();
        });
        projectField.textProperty().addListener((obs, old, val) -> refreshPreview());
        projectField.setOnKeyTyped(e -> projectChosen = true);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        ColumnConstraints grow = new ColumnConstraints();
        grow.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(new ColumnConstraints(), grow, new ColumnConstraints());
        grid.addRow(0, label("MIRAGE output:"), folderField, browseFolder);
        grid.addRow(1, label("Cells:"), geometryBox);
        grid.addRow(2, label("Project folder:"), projectField, browseProject);
        Label geometryHint = new Label("Measurements are the same either way; whole-cell only is lighter on large slides.");
        geometryHint.getStyleClass().add("fp-hint");
        grid.add(geometryHint, 1, 3, 2, 1);

        TableColumn<MirageRun.Patient, String> idCol = column("Patient", 120, p -> p.id());
        TableColumn<MirageRun.Patient, String> cellsCol = column("Cells file", 190,
                p -> p.cells() == null ? "—" : p.cells().getFileName().toString());
        TableColumn<MirageRun.Patient, String> statusCol = column("Status", 280,
                p -> p.note() == null ? p.status().toString() : p.status() + " — " + p.note());
        table.getColumns().addAll(List.of(idCol, cellsCol, statusCol));
        table.setPlaceholder(hint("Choose a MIRAGE output folder to list its patients."));
        table.setPrefHeight(260);
        summary.getStyleClass().add("fp-muted");

        VBox content = new VBox(10, grid, table, summary);
        content.setPadding(new Insets(10));
        content.setPrefWidth(640);
        VBox.setVgrow(table, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);

        Path last = MiragePrefs.lastFolder(prefs);
        if (last != null && Files.isDirectory(last)) setFolder(last);
        refreshPreview();
    }

    /** Show the dialog; empty when cancelled. */
    static Optional<Request> show(Window owner) {
        MirageImportDialog d = new MirageImportDialog(owner);
        Optional<ButtonType> answer = d.dialog.showAndWait();
        if (answer.isEmpty() || answer.get() != d.importType || d.run == null) return Optional.empty();
        MiragePrefs.setLastFolder(d.prefs, d.run.folder());
        List<MirageRun.Patient> ready = d.run.ready();
        return Optional.of(new Request(d.projectDir(), ready, d.run.patients().size() - ready.size()));
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("MIRAGE output folder");
        Path current = run != null ? run.folder() : MiragePrefs.lastFolder(prefs);
        if (current != null && Files.isDirectory(current)) chooser.setInitialDirectory(current.toFile());
        File chosen = chooser.showDialog(dialog.getOwner());
        if (chosen != null) setFolder(chosen.toPath());
    }

    private void chooseProject() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("QuPath project folder (empty, or holding a project)");
        Path current = projectDir();
        Path start = current != null && Files.isDirectory(current) ? current
                : current != null && current.getParent() != null && Files.isDirectory(current.getParent())
                ? current.getParent() : null;
        if (start != null) chooser.setInitialDirectory(start.toFile());
        File chosen = chooser.showDialog(dialog.getOwner());
        if (chosen != null) {
            projectChosen = true;
            projectField.setText(chosen.getAbsolutePath());
        }
    }

    private void setFolder(Path folder) {
        folderField.setText(folder.toString());
        rescan();
        if (run != null && !projectChosen) projectField.setText(run.suggestedProjectDir().toString());
    }

    /** Re-read the folder under the current cells choice and target project. */
    private void rescan() {
        String text = folderField.getText();
        if (text == null || text.isBlank()) {
            run = null;
        } else {
            try {
                run = MirageRun.scan(Path.of(text), geometryBox.getValue(), existingNames());
            } catch (IOException e) {
                run = null;
                summary.setText(e.getMessage());
            }
        }
        table.getItems().setAll(run == null ? List.of() : run.patients());
        updateSummary();
    }

    /** The target changed: the same folder, re-judged against the names already in it. */
    private void refreshPreview() {
        if (run != null) rescan();
        else updateSummary();
    }

    private Set<String> existingNames() {
        Path dir = projectDir();
        if (dir == null) return Set.of();
        try {
            return ProjectBuilder.existingImageNames(dir);
        } catch (IOException e) {
            return Set.of();
        }
    }

    private Path projectDir() {
        String text = projectField.getText();
        return text == null || text.isBlank() ? null : Path.of(text.strip());
    }

    private void updateSummary() {
        Button importButton = (Button) dialog.getDialogPane().lookupButton(importType);
        if (run == null) {
            if (folderField.getText() == null || folderField.getText().isBlank()) summary.setText("");
            importButton.setDisable(true);
            return;
        }
        if (run.patients().isEmpty()) {
            summary.setText("No MIRAGE patients here: expected <folder>/<patient>/pyramid/" + MirageRun.PYRAMID + ".");
            importButton.setDisable(true);
            return;
        }
        long ready = run.ready().size();
        long inProject = run.patients().stream().filter(p -> p.status() == MirageRun.Status.ALREADY_IN_PROJECT).count();
        long noCells = run.patients().stream().filter(p -> p.status() == MirageRun.Status.NO_CELLS).count();
        String target = projectDir() == null ? "" : Files.isRegularFile(projectDir().resolve(ProjectBuilder.PROJECT_FILE))
                ? " · adding to the existing project" : " · a new project will be created";
        summary.setText(ready + " to add · " + inProject + " already in the project · " + noCells + " without a cells file"
                + target);
        importButton.setDisable(ready == 0 || projectDir() == null);
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("fp-primary-text");
        return l;
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("fp-hint");
        return l;
    }

    private static TableColumn<MirageRun.Patient, String> column(String title, double width,
                                                                   java.util.function.Function<MirageRun.Patient, String> value) {
        TableColumn<MirageRun.Patient, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        return col;
    }
}
