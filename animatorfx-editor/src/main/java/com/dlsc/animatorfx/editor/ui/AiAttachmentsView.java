package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiAttachment;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects the files a user attaches to an AI generation, either through a file chooser or by drag and drop.
 */
final class AiAttachmentsView extends VBox {
    private final ObservableList<AiAttachment> attachments = FXCollections.observableArrayList();
    private final ObservableList<AiAttachment> readOnly = FXCollections.unmodifiableObservableList(attachments);
    private final FlowPane chips = new FlowPane(6, 6);
    private final Label error = new Label();

    AiAttachmentsView() {
        setId("ai-attachments");
        getStyleClass().add("ai-attachments");
        setSpacing(8);

        Label title = new Label("Attachments");
        title.getStyleClass().add("ai-attachments-title");
        Label hint = new Label("Optional · PNG, JPEG, GIF or SVG · drop files here");
        hint.getStyleClass().add("ai-attachments-hint");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button add = new Button("Add Files…", new FontIcon(Material2AL.ATTACH_FILE));
        add.setId("ai-add-attachments");
        add.setOnAction(event -> choose());
        HBox header = new HBox(8, title, hint, spacer, add);
        header.setAlignment(Pos.CENTER_LEFT);

        Label usage = new Label("The AI places attachments in the animation or uses them as design references. "
                + "Say in your description how they should be used.");
        usage.getStyleClass().add("ai-attachments-hint");
        usage.setWrapText(true);

        error.setId("ai-attachment-error");
        error.getStyleClass().add("ai-attachment-error");
        error.setWrapText(true);
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);

        chips.getStyleClass().add("ai-attachment-chips");
        chips.managedProperty().bind(chips.visibleProperty());
        chips.setVisible(false);

        getChildren().addAll(header, usage, chips, error);

        setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) event.acceptTransferModes(TransferMode.COPY);
            event.consume();
        });
        setOnDragDropped(event -> {
            boolean files = event.getDragboard().hasFiles();
            if (files) add(event.getDragboard().getFiles().stream().map(File::toPath).toList());
            event.setDropCompleted(files);
            event.consume();
        });
    }

    /**
     * Returns the attached files in the order they were added.
     *
     * @return a read-only list of the attachments
     */
    ObservableList<AiAttachment> getAttachments() {
        return readOnly;
    }

    /**
     * Reads and attaches the given files. Files that cannot be attached are skipped and reported.
     */
    void add(List<Path> files) {
        Set<String> errors = new LinkedHashSet<>();
        for (Path file : files) {
            if (attachments.size() >= AiAttachment.MAX_COUNT) {
                errors.add("At most " + AiAttachment.MAX_COUNT + " attachments.");
                break;
            }
            try {
                AiAttachment attachment = AiAttachment.read(file);
                attachments.add(attachment);
                chips.getChildren().add(chip(attachment));
            } catch (IOException ex) {
                errors.add(ex.getMessage());
            }
        }
        error.setText(String.join("\n", errors));
        error.setVisible(!errors.isEmpty());
        chips.setVisible(!attachments.isEmpty());
    }

    String getError() {
        return error.getText();
    }

    private void choose() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach Files");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images (PNG, JPEG, GIF, SVG)",
                "*.png", "*.jpg", "*.jpeg", "*.gif", "*.svg"));
        List<File> selected = chooser.showOpenMultipleDialog(getScene() == null ? null : getScene().getWindow());
        if (selected != null) add(selected.stream().map(File::toPath).toList());
    }

    private HBox chip(AiAttachment attachment) {
        ImageView thumbnail = new ImageView(new Image(new ByteArrayInputStream(attachment.preview()),
                56, 28, true, true));
        thumbnail.getStyleClass().add("ai-attachment-thumbnail");
        Label name = new Label(attachment.name());
        name.getStyleClass().add("ai-attachment-name");
        name.setMaxWidth(160);
        Label size = new Label(Math.round(attachment.width()) + "×" + Math.round(attachment.height())
                + (attachment.kind() == AiAttachment.Kind.VECTOR ? " · SVG" : ""));
        size.getStyleClass().add("ai-attachment-size");
        VBox text = new VBox(name, size);
        Button remove = new Button(null, new FontIcon(Material2AL.CLOSE));
        remove.getStyleClass().add("ai-attachment-remove");
        remove.setTooltip(new Tooltip("Remove " + attachment.name()));
        HBox chip = new HBox(8, thumbnail, text, remove);
        chip.getStyleClass().add("ai-attachment-chip");
        chip.setAlignment(Pos.CENTER_LEFT);
        remove.setOnAction(event -> {
            attachments.remove(attachment);
            chips.getChildren().remove(chip);
            chips.setVisible(!attachments.isEmpty());
            error.setVisible(false);
            error.setText("");
        });
        return chip;
    }
}
