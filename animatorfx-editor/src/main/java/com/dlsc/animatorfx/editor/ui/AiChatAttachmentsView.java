package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiAttachment;
import com.dlsc.animatorfx.editor.ai.AiChatAttachment;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.io.AnimationFile;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
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
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Collects project and external visuals for one AI chat message. */
final class AiChatAttachmentsView extends VBox {
    private final ObservableList<AiChatAttachment> attachments = FXCollections.observableArrayList();
    private final ObservableList<AiChatAttachment> readOnly =
            FXCollections.unmodifiableObservableList(attachments);
    private final FlowPane chips = new FlowPane(6, 6);
    private final Label error = new Label();
    private final ComboBox<Asset> projectImages = new ComboBox<>();
    private final Map<String, String> projectErrors = new LinkedHashMap<>();
    private AnimationFile project;
    private boolean updatingPicker;

    AiChatAttachmentsView() {
        setId("ai-chat-attachments");
        getStyleClass().addAll("ai-attachments", "ai-chat-attachments");
        setSpacing(6);

        Label title = new Label("Attachments");
        title.getStyleClass().add("ai-attachments-title");
        projectImages.setId("ai-chat-project-images");
        projectImages.getStyleClass().add("ai-chat-project-images");
        projectImages.setPromptText("Project image…");
        projectImages.setConverter(new StringConverter<>() {
            @Override
            public String toString(Asset asset) {
                return asset == null ? "" : asset.name();
            }

            @Override
            public Asset fromString(String string) {
                return projectImages.getItems().stream()
                        .filter(asset -> asset.name().equals(string))
                        .findFirst().orElse(null);
            }
        });
        projectImages.valueProperty().addListener((observable, oldValue, asset) -> {
            if (updatingPicker || project == null || asset == null) return;
            byte[] bytes = project.assets().get(asset.id());
            if (bytes != null) addProjectImage(asset, bytes);
            updatingPicker = true;
            projectImages.getSelectionModel().clearSelection();
            updatingPicker = false;
        });
        Button addFiles = new Button("Add Files…", new FontIcon(Material2AL.ATTACH_FILE));
        addFiles.setId("ai-chat-add-files");
        addFiles.setOnAction(event -> chooseFiles());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, spacer, projectImages, addFiles);
        header.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("Up to 5 project images or external PNG, JPEG, GIF and SVG files.");
        hint.getStyleClass().add("ai-attachments-hint");
        hint.setWrapText(true);

        chips.getStyleClass().add("ai-attachment-chips");
        chips.managedProperty().bind(chips.visibleProperty());
        chips.setVisible(false);

        error.setId("ai-chat-attachment-error");
        error.getStyleClass().add("ai-attachment-error");
        error.setWrapText(true);
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);

        getChildren().addAll(header, hint, chips, error);

        setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) event.acceptTransferModes(TransferMode.COPY);
            event.consume();
        });
        setOnDragDropped(event -> {
            boolean hasFiles = event.getDragboard().hasFiles();
            if (hasFiles) {
                addFiles(event.getDragboard().getFiles().stream().map(File::toPath).toList());
            }
            event.setDropCompleted(hasFiles);
            event.consume();
        });
    }

    ObservableList<AiChatAttachment> getAttachments() {
        return readOnly;
    }

    void setProject(AnimationFile file) {
        AnimationFile previous = project;
        project = file;
        updatingPicker = true;
        projectImages.getItems().clear();
        if (file != null) {
            file.animation().assets().stream()
                    .filter(asset -> asset.kind() == Asset.Kind.IMAGE)
                    .filter(asset -> file.assets().containsKey(asset.id()))
                    .forEach(projectImages.getItems()::add);
        }
        projectImages.getSelectionModel().clearSelection();
        projectImages.setDisable(projectImages.getItems().isEmpty());
        updatingPicker = false;
        updateProjectAttachments(file, previous, false);
    }

    boolean validateProjectAttachments(AnimationFile file) {
        return updateProjectAttachments(file, project, true);
    }

    private boolean updateProjectAttachments(AnimationFile file, AnimationFile previous, boolean force) {
        boolean changed = false;
        for (int i = 0; i < attachments.size(); i++) {
            AiChatAttachment attachment = attachments.get(i);
            Asset source = attachment.projectAsset();
            if (source == null) continue;
            Asset current = file == null ? null : file.animation().assets().stream()
                    .filter(asset -> asset.id().equals(source.id()) && asset.kind() == Asset.Kind.IMAGE)
                    .findFirst().orElse(null);
            byte[] bytes = file == null ? null : file.assets().get(source.id());
            if (!force && source.equals(current) && previous != null
                    && Arrays.equals(bytes, previous.assets().get(source.id()))
                    && !projectErrors.containsKey(source.id())) continue;
            changed = true;
            try {
                if (current == null || bytes == null) {
                    throw new IOException(attachment.name() + ": project image is no longer available.");
                }
                attachments.set(i, AiChatAttachment.projectImage(current, bytes));
                projectErrors.remove(source.id());
            } catch (IOException | RuntimeException ex) {
                projectErrors.put(source.id(), errorMessage(ex, attachment.name()));
            }
        }
        projectErrors.keySet().removeIf(id -> attachments.stream()
                .noneMatch(attachment -> attachment.projectAsset() != null
                        && attachment.projectAsset().id().equals(id)));
        if (changed) {
            chips.getChildren().setAll(attachments.stream().map(this::chip).toList());
            showError(String.join("\n", projectErrors.values()));
        }
        return projectErrors.isEmpty();
    }

    void addFiles(List<Path> files) {
        Set<String> failures = new LinkedHashSet<>();
        for (Path file : files) {
            if (atCapacity()) {
                failures.add(limitMessage());
                break;
            }
            try {
                addAttachment(AiChatAttachment.external(AiAttachment.read(file)));
            } catch (IOException ex) {
                failures.add(errorMessage(ex, file.getFileName().toString()));
            }
        }
        showError(String.join("\n", failures));
    }

    void addProjectImage(Asset asset, byte[] bytes) {
        try {
            addAttachment(AiChatAttachment.projectImage(asset, bytes));
        } catch (IOException | IllegalArgumentException ex) {
            showError(errorMessage(ex, asset == null ? "Project image" : asset.name()));
        }
    }

    void addAttachment(AiChatAttachment attachment) {
        if (atCapacity()) {
            showError(limitMessage());
            return;
        }
        attachments.add(attachment);
        chips.getChildren().add(chip(attachment));
        chips.setVisible(true);
        showError("");
    }

    void clear() {
        attachments.clear();
        projectErrors.clear();
        chips.getChildren().clear();
        chips.setVisible(false);
        showError("");
    }

    String getError() {
        return error.getText();
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach Files");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images (PNG, JPEG, GIF, SVG)",
                "*.png", "*.jpg", "*.jpeg", "*.gif", "*.svg"));
        List<File> selected = chooser.showOpenMultipleDialog(getScene() == null ? null : getScene().getWindow());
        if (selected != null) addFiles(selected.stream().map(File::toPath).toList());
    }

    private HBox chip(AiChatAttachment attachment) {
        boolean unavailable = attachment.projectAsset() != null
                && projectErrors.containsKey(attachment.projectAsset().id());
        ImageView thumbnail = new ImageView(unavailable ? null
                : new Image(new ByteArrayInputStream(attachment.preview()), 48, 28, true, true));
        thumbnail.getStyleClass().add("ai-attachment-thumbnail");
        Label name = new Label(attachment.name());
        name.getStyleClass().add("ai-attachment-name");
        name.setMaxWidth(150);
        Label detail = new Label(unavailable ? "Unavailable · Project"
                : Math.round(attachment.width()) + "×" + Math.round(attachment.height())
                + (attachment.kind() == AiAttachment.Kind.VECTOR ? " · SVG" : "")
                + (attachment.projectAsset() != null ? " · Project" : ""));
        detail.getStyleClass().add("ai-attachment-size");
        Button remove = new Button(null, new FontIcon(Material2AL.CLOSE));
        remove.getStyleClass().add("ai-attachment-remove");
        remove.setTooltip(new Tooltip("Remove " + attachment.name()));
        HBox chip = new HBox(8, thumbnail, new VBox(name, detail), remove);
        chip.getStyleClass().add("ai-attachment-chip");
        chip.setAlignment(Pos.CENTER_LEFT);
        remove.setOnAction(event -> {
            attachments.remove(attachment);
            chips.getChildren().remove(chip);
            chips.setVisible(!attachments.isEmpty());
            showError("");
        });
        return chip;
    }

    private boolean atCapacity() {
        return attachments.size() >= AiAttachment.MAX_COUNT;
    }

    private String limitMessage() {
        return "At most " + AiAttachment.MAX_COUNT + " attachments.";
    }

    private void showError(String message) {
        error.setText(message);
        error.setVisible(!message.isEmpty());
    }

    private static String errorMessage(Exception ex, String fallback) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? fallback + ": could not be attached." : message;
    }
}
