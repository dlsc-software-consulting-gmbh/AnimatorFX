package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiAttachment;
import com.dlsc.animatorfx.editor.ai.AiErrors;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import com.dlsc.animatorfx.editor.ai.AiUsage;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.gemsfx.DialogPane;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class AiActions {
    @FunctionalInterface
    interface Generator {
        CompletableFuture<AnimationFile> start(AiSettings settings, String prompt, List<AiAttachment> attachments,
                                               AiUsage usage);
    }

    private final DialogLayer layer;
    private final AiNotifications notifications;
    private final Supplier<AiSettings> settings;
    private final Generator generator;
    private boolean disposed;

    AiActions(DialogLayer layer, AiNotifications notifications, Supplier<AiSettings> settings, Generator generator) {
        this.layer = layer;
        this.notifications = notifications;
        this.settings = settings;
        this.generator = generator;
    }

    void generate() {
        if (disposed) return;
        AiSettings selected;
        try {
            selected = settings.get();
        } catch (RuntimeException ex) {
            error("Could not read AI preferences: " + AiErrors.message(ex));
            return;
        }
        TextArea description = new TextArea();
        description.setId("ai-description");
        description.setPromptText("Describe the animation or scene (up to 8,000 characters)");
        description.setWrapText(true);
        description.setPrefRowCount(6);
        Label disclosure = new Label("Your description and attachments will be sent to the selected AI provider. "
                + "Provider charges may apply.");
        disclosure.setWrapText(true);
        String provider = switch (selected.provider()) {
            case OPENAI -> "OpenAI";
            case ANTHROPIC -> "Anthropic";
        };
        Label model = new Label("Provider: " + provider + "  ·  Model: " + selected.model());
        model.setId("ai-generation-model");
        model.setWrapText(true);
        AiAttachmentsView view = new AiAttachmentsView();
        VBox content = new VBox(12, model, disclosure, description, view);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType generate = new ButtonType("Generate", ButtonBar.ButtonData.OK_DONE);
        DialogPane.Dialog<Void> dialog = layer.dialog(DialogPane.Type.CONFIRMATION, "Generate Animation",
                content, 540, List.of(cancel, generate));
        if (layer.show(dialog, button -> {
            if (button == generate) {
                String prompt = description.getText().trim();
                List<AiAttachment> attachments = List.copyOf(view.getAttachments());
                if (!prompt.isEmpty()) Platform.runLater(() -> {
                    if (!disposed) notifications.start(selected, prompt, attachments, generator);
                });
            }
        })) {
            dialog.getButton(generate).setId("ai-generate");
            dialog.getButton(generate).disableProperty().bind(Bindings.createBooleanBinding(
                    () -> description.getText().isBlank() || description.getText().length() > 8000,
                    description.textProperty()));
            description.requestFocus();
        }
    }

    void dispose() {
        disposed = true;
        notifications.dispose();
    }

    private void error(String message) {
        Label label = new Label(message);
        label.setWrapText(true);
        ScrollPane scroll = new ScrollPane(label);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(250);
        ButtonType close = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        layer.show(layer.dialog(DialogPane.Type.WARNING, "AI Generation", scroll, 440, List.of(close)), ignored -> {});
    }
}
