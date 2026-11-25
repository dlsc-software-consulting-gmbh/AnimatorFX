package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiProviderClient.Provider;
import com.dlsc.animatorfx.editor.ai.AiErrors;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import com.dlsc.gemsfx.DialogPane;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

final class AiPreferencesDialog {
    private final DialogLayer layer;
    private final Path file;
    private final BiConsumer<String, String> putKey;
    private final Consumer<String> deleteKey;
    private final Function<String, String> readKey;
    private final BiFunction<Provider, String, CompletableFuture<List<String>>> listModels;
    private final Runnable onSaved;

    AiPreferencesDialog(DialogLayer layer, Path file, BiConsumer<String, String> putKey, Consumer<String> deleteKey,
                        Function<String, String> readKey,
                        BiFunction<Provider, String, CompletableFuture<List<String>>> listModels) {
        this(layer, file, putKey, deleteKey, readKey, listModels, () -> {});
    }

    AiPreferencesDialog(DialogLayer layer, Path file, BiConsumer<String, String> putKey, Consumer<String> deleteKey,
                        Function<String, String> readKey,
                        BiFunction<Provider, String, CompletableFuture<List<String>>> listModels, Runnable onSaved) {
        this.layer = layer;
        this.file = file;
        this.putKey = putKey;
        this.deleteKey = deleteKey;
        this.readKey = readKey;
        this.listModels = listModels;
        this.onSaved = onSaved;
    }

    void show() {
        AiSettings settings;
        try {
            settings = AiSettings.load(file);
        } catch (IOException ex) {
            error("Could not read AI preferences: " + AiErrors.message(ex));
            return;
        }
        ChoiceBox<Provider> provider = new ChoiceBox<>();
        provider.setId("ai-provider");
        provider.getItems().setAll(Provider.values());
        provider.setValue(settings.provider());
        PasswordField openAiKey = new PasswordField();
        openAiKey.setId("ai-openai-key");
        openAiKey.setPromptText("Leave empty to keep the current key");
        PasswordField anthropicKey = new PasswordField();
        anthropicKey.setId("ai-anthropic-key");
        anthropicKey.setPromptText("Leave empty to keep the current key");
        CheckBox removeOpenAi = new CheckBox("Remove saved OpenAI key");
        CheckBox removeAnthropic = new CheckBox("Remove saved Anthropic key");
        openAiKey.disableProperty().bind(removeOpenAi.selectedProperty());
        anthropicKey.disableProperty().bind(removeAnthropic.selectedProperty());
        ModelField openAiModel = new ModelField(Provider.OPENAI, settings.openAiModel(), openAiKey, removeOpenAi);
        ModelField anthropicModel = new ModelField(Provider.ANTHROPIC, settings.anthropicModel(), anthropicKey,
                removeAnthropic);
        Label explanation = new Label("API keys are stored in your operating system's secure credential store. "
                + "They are not saved in animation files.");
        explanation.setWrapText(true);
        Label compatibility = new Label("Some listed models may not support structured animation generation.");
        compatibility.setWrapText(true);
        CheckBox visualReview = new CheckBox("Enable AI visual review. Rendered frames may include embedded content "
                + "sent to the provider and may incur extra costs.");
        visualReview.setId("ai-visual-review");
        visualReview.setWrapText(true);
        visualReview.setSelected(settings.visualReview());
        VBox openAiFields = new VBox(8, new Label("OpenAI model"), openAiModel.row, openAiModel.status,
                new Label("OpenAI API key"), openAiKey, removeOpenAi);
        VBox anthropicFields = new VBox(8, new Label("Anthropic model"), anthropicModel.row, anthropicModel.status,
                new Label("Anthropic API key"), anthropicKey, removeAnthropic);
        openAiFields.visibleProperty().bind(provider.valueProperty().isEqualTo(Provider.OPENAI));
        openAiFields.managedProperty().bind(openAiFields.visibleProperty());
        anthropicFields.visibleProperty().bind(provider.valueProperty().isEqualTo(Provider.ANTHROPIC));
        anthropicFields.managedProperty().bind(anthropicFields.visibleProperty());
        VBox content = new VBox(8, explanation, new Label("Provider"), provider,
                compatibility, openAiFields, anthropicFields, visualReview);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(300);
        scroll.setMaxHeight(330);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        DialogPane.Dialog<Void> dialog = layer.dialog(DialogPane.Type.CONFIRMATION, "AI Preferences", scroll,
                540, List.of(cancel, save));
        if (layer.show(dialog, button -> {
            openAiModel.close();
            anthropicModel.close();
            if (button != save) return;
            AiSettings updated;
            try {
                updated = new AiSettings(provider.getValue(), openAiModel.model.getEditor().getText().trim(),
                        anthropicModel.model.getEditor().getText().trim(), visualReview.isSelected());
            } catch (RuntimeException ex) {
                Platform.runLater(() -> error("Enter a model name for each provider."));
                return;
            }
            String openAiSecret = openAiKey.getText().trim();
            String anthropicSecret = anthropicKey.getText().trim();
            boolean deleteOpenAi = removeOpenAi.isSelected();
            boolean deleteAnthropic = removeAnthropic.isSelected();
            Platform.runLater(() -> save(updated, openAiSecret, anthropicSecret, deleteOpenAi, deleteAnthropic));
        })) {
            dialog.getButton(save).setId("ai-save-preferences");
            openAiModel.refresh();
            anthropicModel.refresh();
        }
    }

    private final class ModelField {
        private final Provider provider;
        private final PasswordField key;
        private final ComboBox<String> model = new ComboBox<>();
        private final Button refresh = new Button("Refresh");
        private final Label status = new Label();
        private final HBox row = new HBox(8, model, refresh);
        private CompletableFuture<List<String>> active;
        private int revision;
        private boolean closed;

        private ModelField(Provider provider, String selected, PasswordField key, CheckBox remove) {
            this.provider = provider;
            this.key = key;
            String id = provider == Provider.OPENAI ? "openai" : "anthropic";
            model.setId("ai-" + id + "-model");
            model.setEditable(true);
            model.setMaxWidth(Double.MAX_VALUE);
            model.setValue(selected);
            model.getEditor().setText(selected);
            HBox.setHgrow(model, Priority.ALWAYS);
            refresh.setId("ai-" + id + "-refresh");
            refresh.disableProperty().bind(remove.selectedProperty());
            refresh.setOnAction(event -> refresh());
            status.setWrapText(true);
            status.setId("ai-" + id + "-model-status");
            key.textProperty().addListener((obs, oldValue, newValue) -> invalidate());
            remove.selectedProperty().addListener((obs, oldValue, newValue) -> invalidate());
        }

        private void invalidate() {
            revision++;
            if (active != null) active.cancel(true);
            active = null;
            String value = model.getEditor().getText();
            model.getItems().clear();
            model.setValue(value);
            model.getEditor().setText(value);
            status.setText("Refresh to load models, or enter a model ID.");
        }

        private void refresh() {
            invalidate();
            int current = revision;
            status.setText("Loading available models…");
            String entered = key.getText().trim();
            CompletableFuture<List<String>> result = new CompletableFuture<>();
            active = result;
            CompletableFuture.supplyAsync(() -> entered.isEmpty() ? readKey.apply(provider.name()) : entered)
                    .whenComplete((secret, failure) -> {
                        if (result.isDone()) return;
                        if (failure != null) {
                            result.completeExceptionally(failure);
                            return;
                        }
                        if (secret == null || secret.isBlank()) {
                            result.complete(null);
                            return;
                        }
                        try {
                            CompletableFuture<List<String>> network = listModels.apply(provider, secret);
                            result.whenComplete((ignored, error) -> {
                                if (result.isCancelled()) network.cancel(true);
                            });
                            network.whenComplete((models, error) -> {
                                if (error != null) result.completeExceptionally(error);
                                else result.complete(models);
                            });
                        } catch (RuntimeException ex) {
                            result.completeExceptionally(ex);
                        }
                    });
            result.whenComplete((models, failure) -> Platform.runLater(() -> {
                if (closed || current != revision) return;
                active = null;
                if (failure != null || models == null) {
                    status.setText(failure == null ? "No API key is available. Enter a key or a model ID."
                            : "Could not load models: " + AiErrors.message(failure) + " Enter a model ID or refresh.");
                } else {
                    String value = model.getEditor().getText();
                    model.getItems().setAll(models);
                    model.setValue(value);
                    model.getEditor().setText(value);
                    status.setText(models.isEmpty() ? "No models available; enter a model ID."
                            : models.size() + " models available.");
                }
            }));
        }

        private void close() {
            closed = true;
            revision++;
            if (active != null) active.cancel(true);
            active = null;
        }
    }

    private void save(AiSettings settings, String openAiKey, String anthropicKey,
                      boolean removeOpenAi, boolean removeAnthropic) {
        ButtonType close = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        DialogPane.Dialog<Void> progress = layer.dialog(DialogPane.Type.INFORMATION, "Saving AI Preferences",
                new Label("Saving settings and secure credentials…"), 400, List.of(close));
        if (!layer.show(progress, ignored -> {})) return;
        progress.getButton(close).setDisable(true);
        CompletableFuture.runAsync(() -> {
            if (removeOpenAi) deleteKey.accept(Provider.OPENAI.name());
            else if (!openAiKey.isEmpty()) putKey.accept(Provider.OPENAI.name(), openAiKey);
            if (removeAnthropic) deleteKey.accept(Provider.ANTHROPIC.name());
            else if (!anthropicKey.isEmpty()) putKey.accept(Provider.ANTHROPIC.name(), anthropicKey);
            try {
                settings.save(file);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }).whenComplete((unused, failure) -> Platform.runLater(() -> {
            progress.cancel();
            onSaved.run();
            if (failure != null) Platform.runLater(() -> error(
                    "Could not save AI preferences or access the secure credential store: " + AiErrors.message(failure)));
        }));
    }

    private void error(String message) {
        Label text = new Label(message);
        text.setWrapText(true);
        ButtonType close = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        layer.show(layer.dialog(DialogPane.Type.WARNING, "AI Preferences", text, 440, List.of(close)), ignored -> {});
    }
}
