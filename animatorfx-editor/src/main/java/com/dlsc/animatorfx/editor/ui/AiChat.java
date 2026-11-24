package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiEdits;
import com.dlsc.animatorfx.editor.ai.AiEditService;
import com.dlsc.animatorfx.editor.ai.AiErrors;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Animation;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class AiChat extends VBox {
    @FunctionalInterface
    interface Request {
        CompletableFuture<AiEdits.Result> edit(AiSettings settings, Animation animation, List<String> selection,
                                                double time, List<AiEdits.Message> history, String prompt);
    }

    private final Workspace workspace;
    private final Supplier<AiSettings> settings;
    private final Request request;
    private final Consumer<String> activity;
    private final Map<EditorDocument, List<AiEdits.Message>> conversations = new IdentityHashMap<>();
    private final Map<EditorDocument, Map<AiEdits.Message, AiFeedback>> feedback = new IdentityHashMap<>();
    private final TextArea transcript = new TextArea();
    private final VBox feedbackCards = new VBox(8);
    private final ScrollPane feedbackScroll = new ScrollPane(feedbackCards);
    private final TextArea input = new TextArea();
    private final Label provider = new Label();
    private final Label document = new Label();
    private final Button send = new Button("Send");
    private final Button cancel = new Button("Cancel");
    private final Button clear = new Button("Clear");
    private Pending active;
    private boolean disposed;
    private final ChangeListener<EditorDocument> documentChanged = (obs, oldDoc, newDoc) -> {
        input.clear();
        refresh();
    };
    private final ListChangeListener<EditorDocument> documentsChanged = change -> {
        while (change.next()) {
            for (EditorDocument removed : change.getRemoved()) {
                conversations.remove(removed);
                removeFeedback(removed);
                if (active != null && active.document == removed) active.future.cancel(true);
            }
        }
        refresh();
    };
    private static final class Pending {
        final EditorDocument document;
        final Animation snapshot;
        final CompletableFuture<AiEdits.Result> future;
        final String context;
        final InvalidationListener changed = ignored -> stale = true;
        boolean stale;

        Pending(EditorDocument document, Animation snapshot, CompletableFuture<AiEdits.Result> future, String context) {
            this.document = document;
            this.snapshot = snapshot;
            this.future = future;
            this.context = context;
        }
    }

    AiChat(Workspace workspace, Supplier<AiSettings> settings, Request request, Consumer<String> activity) {
        this.workspace = workspace;
        this.settings = settings;
        this.request = request;
        this.activity = activity;
        setSpacing(8);
        setPadding(new Insets(10));
        provider.setWrapText(true);
        document.setWrapText(true);
        Label disclosure = new Label("Project content and recent chat are sent to the provider. Charges may apply.");
        disclosure.setWrapText(true);
        transcript.setId("ai-chat-transcript");
        transcript.setEditable(false);
        transcript.setWrapText(true);
        transcript.setPromptText("Describe a change to the current project.");
        VBox.setVgrow(transcript, Priority.ALWAYS);
        input.setId("ai-chat-input");
        input.setWrapText(true);
        input.setPrefRowCount(3);
        input.setPromptText("Describe a change (up to 8,000 characters)");
        input.textProperty().addListener(ignored -> updateControls());
        input.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && event.isShortcutDown() && !send.isDisabled()) {
                submit();
                event.consume();
            }
        });
        send.setId("ai-chat-send");
        cancel.setId("ai-chat-cancel");
        clear.setId("ai-chat-clear");
        clear.setTooltip(new Tooltip("Clear conversation"));
        send.setOnAction(event -> submit());
        cancel.setOnAction(event -> { if (active != null) active.future.cancel(true); });
        clear.setOnAction(event -> {
            conversations.remove(workspace.getActiveDocument());
            removeFeedback(workspace.getActiveDocument());
            refresh();
        });
        feedbackScroll.setFitToWidth(true);
        feedbackScroll.setPrefViewportHeight(180);
        feedbackScroll.setMaxHeight(240);
        feedbackScroll.setId("ai-feedback");
        HBox buttons = new HBox(6, send, cancel, clear);
        getChildren().addAll(provider, document, disclosure, transcript, feedbackScroll, input, buttons);
        workspace.activeDocumentProperty().addListener(documentChanged);
        workspace.getDocuments().addListener(documentsChanged);
        refresh();
    }

    void refresh() {
        EditorDocument current = workspace.getActiveDocument();
        document.setText(current == null ? "No project open" : "Project: " + current.getTitle());
        try {
            AiSettings selected = settings.get();
            provider.setText("Provider: " + selected.provider() + "  ·  Model: " + selected.model());
        } catch (RuntimeException ex) {
            provider.setText("AI preferences are unavailable.");
        }
        StringBuilder text = new StringBuilder();
        for (AiEdits.Message message : conversations.getOrDefault(current, List.of())) {
            text.append(message.role().equals("user") ? "You: " : "AI: ").append(message.text()).append("\n\n");
        }
        transcript.setText(text.toString());
        transcript.positionCaret(text.length());
        Map<AiEdits.Message, AiFeedback> cardByMessage = feedback.getOrDefault(current, Map.of());
        List<AiFeedback> cards = conversations.getOrDefault(current, List.of()).stream()
                .map(cardByMessage::get).filter(java.util.Objects::nonNull).toList();
        feedbackCards.getChildren().setAll(cards);
        feedbackScroll.setVisible(!cards.isEmpty());
        feedbackScroll.setManaged(!cards.isEmpty());
        updateControls();
    }

    private void updateControls() {
        boolean busy = active != null || disposed;
        boolean unavailable = workspace.getActiveDocument() == null;
        send.setDisable(busy || unavailable || input.getText().isBlank() || input.getText().length() > 8000);
        input.setDisable(busy || unavailable);
        cancel.setDisable(!busy || disposed);
        clear.setDisable(busy || unavailable);
    }

    private void submit() {
        if (disposed || active != null || workspace.getActiveDocument() == null
                || input.getText().isBlank() || input.getText().length() > 8000) return;
        EditorDocument target = workspace.getActiveDocument();
        String prompt = input.getText().trim();
        AiSettings selected;
        try {
            selected = settings.get();
        } catch (RuntimeException ex) {
            append(target, "assistant", "Could not read AI preferences.");
            return;
        }
        Animation snapshot = target.getAnimation();
        List<AiEdits.Message> history = List.copyOf(conversations.getOrDefault(target, List.of()));
        append(target, "user", prompt);
        input.clear();
        String context = selected.provider() + " / " + selected.model();
        activity.accept("AI edit started: " + context);
        CompletableFuture<AiEdits.Result> future;
        try {
            future = request.edit(selected, snapshot, List.copyOf(target.getSelection().layerIds()),
                    target.getPlayhead(), history, prompt);
        } catch (RuntimeException ex) {
            activity.accept("AI edit failed: " + context);
            append(target, "assistant", "Could not start the AI request.");
            return;
        }
        Pending pending = new Pending(target, snapshot, future, context);
        active = pending;
        target.animationProperty().addListener(pending.changed);
        updateControls();
        future.whenComplete((result, failure) -> Platform.runLater(() -> finish(pending, result, failure)));
    }

    private void finish(Pending pending, AiEdits.Result result, Throwable failure) {
        pending.document.animationProperty().removeListener(pending.changed);
        if (active != pending) return;
        active = null;
        if (disposed || !workspace.getDocuments().contains(pending.document)) {
            updateControls();
            return;
        }
        if (pending.future.isCancelled()) {
            activity.accept("AI edit cancelled: " + pending.context);
            append(pending.document, "assistant", "Request cancelled.");
        } else if (failure != null || result == null) {
            String message = failure == null ? "The AI provider returned an invalid response." : AiErrors.message(failure);
            String activityMessage = failure == null ? message : AiEditService.activityMessage(failure);
            activity.accept("AI edit failed (" + pending.context + "): " + activityMessage);
            append(pending.document, "assistant", message);
        } else if (pending.stale || pending.document.getAnimation() != pending.snapshot) {
            activity.accept("AI edit discarded: the project changed during the request.");
            append(pending.document, "assistant", "The project changed during the request; no AI edits were applied.");
        } else {
            AiFeedback card = null;
            if (!pending.snapshot.equals(result.animation())) {
                pending.document.execute(Commands.setDocument("AI Edit", ignored -> result.animation()));
                if (!result.changes().isEmpty()) {
                    card = new AiFeedback(pending.document, result);
                }
            }
            activity.accept("AI edit completed: " + pending.context);
            AiEdits.Message message = append(pending.document, "assistant", result.reply());
            if (card != null) {
                feedback.computeIfAbsent(pending.document, ignored -> new IdentityHashMap<>()).put(message, card);
            }
        }
        refresh();
    }

    private AiEdits.Message append(EditorDocument target, String role, String text) {
        List<AiEdits.Message> messages = conversations.computeIfAbsent(target, ignored -> new ArrayList<>());
        AiEdits.Message message = new AiEdits.Message(role, text);
        messages.add(message);
        while (messages.size() > 20) {
            AiEdits.Message removed = messages.removeFirst();
            Map<AiEdits.Message, AiFeedback> cards = feedback.get(target);
            AiFeedback card = cards == null ? null : cards.remove(removed);
            if (card != null) card.dispose();
        }
        if (workspace.getActiveDocument() == target) refresh();
        return message;
    }

    void dispose() {
        disposed = true;
        if (active != null) {
            active.document.animationProperty().removeListener(active.changed);
            active.future.cancel(true);
            active = null;
        }
        workspace.activeDocumentProperty().removeListener(documentChanged);
        workspace.getDocuments().removeListener(documentsChanged);
        conversations.clear();
        for (EditorDocument document : List.copyOf(feedback.keySet())) removeFeedback(document);
        refresh();
    }

    private void removeFeedback(EditorDocument document) {
        Map<AiEdits.Message, AiFeedback> removed = feedback.remove(document);
        if (removed != null) removed.values().forEach(AiFeedback::dispose);
    }
}
