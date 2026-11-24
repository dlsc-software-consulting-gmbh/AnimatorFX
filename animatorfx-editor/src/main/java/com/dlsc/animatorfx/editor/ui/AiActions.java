package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiErrors;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.gemsfx.DialogPane;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class AiActions {
    private final DialogLayer layer;
    private final Workspace workspace;
    private final Supplier<AiSettings> settings;
    private final BiFunction<AiSettings, String, CompletableFuture<AnimationFile>> generator;
    private final Consumer<String> activity;
    private CompletableFuture<AnimationFile> active;
    private boolean disposed;

    AiActions(DialogLayer layer, Workspace workspace, Supplier<AiSettings> settings,
              BiFunction<AiSettings, String, CompletableFuture<AnimationFile>> generator) {
        this(layer, workspace, settings, generator, ignored -> {});
    }

    AiActions(DialogLayer layer, Workspace workspace, Supplier<AiSettings> settings,
              BiFunction<AiSettings, String, CompletableFuture<AnimationFile>> generator, Consumer<String> activity) {
        this.layer = layer;
        this.workspace = workspace;
        this.settings = settings;
        this.generator = generator;
        this.activity = activity;
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
        Label disclosure = new Label("Your description will be sent to the selected AI provider. "
                + "Provider charges may apply.");
        disclosure.setWrapText(true);
        String provider = switch (selected.provider()) {
            case OPENAI -> "OpenAI";
            case ANTHROPIC -> "Anthropic";
        };
        Label model = new Label("Provider: " + provider + "  ·  Model: " + selected.model());
        model.setId("ai-generation-model");
        model.setWrapText(true);
        VBox content = new VBox(12, model, disclosure, description);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType generate = new ButtonType("Generate", ButtonBar.ButtonData.OK_DONE);
        DialogPane.Dialog<Void> dialog = layer.dialog(DialogPane.Type.CONFIRMATION, "Generate Animation",
                content, 540, List.of(cancel, generate));
        if (layer.show(dialog, button -> {
            if (button == generate) {
                String prompt = description.getText().trim();
                if (!prompt.isEmpty()) Platform.runLater(() -> start(selected, prompt));
            }
        })) {
            dialog.getButton(generate).setId("ai-generate");
            dialog.getButton(generate).disableProperty().bind(Bindings.createBooleanBinding(
                    () -> description.getText().isBlank() || description.getText().length() > 8000,
                    description.textProperty()));
            description.requestFocus();
        }
    }

    private void start(AiSettings selected, String prompt) {
        if (disposed) return;
        String context = selected.provider() + " / " + selected.model();
        activity.accept("Generation started: " + context);
        CompletableFuture<AnimationFile> future;
        try {
            future = generator.apply(selected, prompt);
        } catch (RuntimeException ex) {
            String message = AiErrors.message(ex).replace(prompt, "[redacted]");
            activity.accept("Generation failed (" + context + "): " + message);
            error("Unable to start generation: " + message);
            return;
        }
        active = future;
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        VBox content = new VBox(12, new Label("Generating an editable animation…"), new ProgressIndicator());
        DialogPane.Dialog<Void> dialog = layer.dialog(DialogPane.Type.INFORMATION, "Generating", content,
                400, List.of(cancel));
        if (!layer.show(dialog, ignored -> {
            if (active == future) active = null;
            if (future.cancel(true)) activity.accept("Generation cancelled: " + context);
        })) {
            active = null;
            if (future.cancel(true)) activity.accept("Generation cancelled: " + context);
            return;
        }
        dialog.getButton(cancel).setId("ai-cancel-generation");
        future.whenComplete((file, failure) -> Platform.runLater(() -> {
            if (disposed || future.isCancelled()) return;
            dialog.cancel();
            if (failure != null || file == null) {
                String message = failure != null ? AiErrors.message(failure)
                        : "Generation failed. Check your settings and try again.";
                activity.accept("Generation failed (" + context + "): " + message.replace(prompt, "[redacted]"));
                Platform.runLater(() -> error(message));
            } else {
                workspace.add(EditorDocument.imported(file));
                activity.accept("Generation completed: " + context);
            }
        }));
    }

    void dispose() {
        disposed = true;
        if (active != null) {
            active.cancel(true);
            active = null;
        }
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
