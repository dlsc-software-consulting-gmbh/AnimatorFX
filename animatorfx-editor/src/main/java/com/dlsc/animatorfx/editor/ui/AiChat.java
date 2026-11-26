package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiChatAttachment;
import com.dlsc.animatorfx.editor.ai.AiEdits;
import com.dlsc.animatorfx.editor.ai.AiEditService;
import com.dlsc.animatorfx.editor.ai.AiErrors;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.io.AnimationFile;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.bootstrapicons.BootstrapIcons;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;

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
        CompletableFuture<AiEdits.Result> edit(AiSettings settings, AnimationFile file, List<String> selection,
                                                double time, List<AiEdits.Message> history, String prompt,
                                                List<AiChatAttachment> attachments);
    }

    private final Workspace workspace;
    private final Supplier<AiSettings> settings;
    private final Request request;
    private final Consumer<String> activity;
    private final Map<EditorDocument, List<AiEdits.Message>> conversations = new IdentityHashMap<>();
    private final Map<EditorDocument, Map<AiEdits.Message, AiFeedback>> feedback = new IdentityHashMap<>();
    private final VBox transcript = new VBox(8);
    private final ScrollPane conversation = new ScrollPane(transcript);
    private final AiChatAttachmentsView attachments = new AiChatAttachmentsView();
    private final Label placeholder = new Label("Describe a change to the current project.");
    private final TextArea input = new TextArea();
    private final Label provider = new Label();
    private final Label model = new Label();
    private final Tooltip modelTip = new Tooltip();
    private final Label document = new Label();
    private final Button send = new Button("Send");
    private final Button cancel = new Button("Cancel");
    private final Button clear = new Button("Clear");
    private final HBox typing = new HBox(4);
    private final Timeline typingAnimation = new Timeline();
    private Pending active;
    private boolean disposed;
    private final ChangeListener<AnimationFile> fileChanged = (obs, oldFile, newFile) ->
            attachments.setProject(newFile);
    private final ChangeListener<EditorDocument> documentChanged = (obs, oldDoc, newDoc) -> {
        if (oldDoc != null) oldDoc.fileContentProperty().removeListener(fileChanged);
        if (newDoc != null) newDoc.fileContentProperty().addListener(fileChanged);
        input.clear();
        attachments.clear();
        attachments.setProject(newDoc == null ? null : newDoc.getFileContent());
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
        VBox context = context();
        buildTypingIndicator();
        transcript.setId("ai-chat-transcript");
        transcript.setPadding(new Insets(8));
        conversation.setId("ai-chat-conversation");
        conversation.setFitToWidth(true);
        conversation.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        placeholder.getStyleClass().add("ai-chat-placeholder");
        placeholder.setWrapText(true);
        placeholder.setMouseTransparent(true);
        StackPane history = new StackPane(conversation, placeholder);
        StackPane.setAlignment(placeholder, Pos.TOP_LEFT);
        StackPane.setMargin(placeholder, new Insets(10));
        VBox.setVgrow(history, Priority.ALWAYS);
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
        HBox buttons = new HBox(6, send, cancel, clear);
        VBox composer = new VBox(6, attachments, input, buttons);
        getChildren().addAll(context, history, composer);
        workspace.activeDocumentProperty().addListener(documentChanged);
        workspace.getDocuments().addListener(documentsChanged);
        if (workspace.getActiveDocument() != null) {
            workspace.getActiveDocument().fileContentProperty().addListener(fileChanged);
        }
        attachments.setProject(workspace.getActiveDocument() == null
                ? null : workspace.getActiveDocument().getFileContent());
        refresh();
    }

    // provider and model, project and the data disclosure, as one card above the conversation
    private VBox context() {
        provider.setId("ai-chat-provider");
        provider.getStyleClass().add("ai-chat-provider");
        provider.setGraphic(new FontIcon(BootstrapIcons.STARS));
        provider.setMinWidth(Region.USE_PREF_SIZE);
        model.setId("ai-chat-model");
        model.getStyleClass().addAll("ai-chat-model", "mono");
        model.setMinWidth(0);
        model.setTooltip(modelTip);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox providerRow = new HBox(6, provider, spacer, model);
        providerRow.setAlignment(Pos.CENTER_LEFT);
        document.setId("ai-chat-project");
        document.getStyleClass().add("ai-chat-project");
        document.setGraphic(new FontIcon(Material2AL.DESCRIPTION));
        Region divider = new Region();
        divider.getStyleClass().add("ai-chat-context-divider");
        Label disclosure = new Label("Project content and recent chat are sent to the provider. Charges may apply.",
                new FontIcon(Material2AL.INFO));
        disclosure.getStyleClass().add("ai-chat-disclosure");
        disclosure.setWrapText(true);
        VBox card = new VBox(6, providerRow, document, divider, disclosure);
        card.getStyleClass().add("ai-chat-context");
        return card;
    }

    // three dots that bounce one after the other in a reply bubble while the AI works on a reply
    private void buildTypingIndicator() {
        typing.setId("ai-chat-typing");
        typing.getStyleClass().addAll("ai-chat-message", "assistant", "ai-chat-typing");
        typing.setAlignment(Pos.CENTER_LEFT);
        typing.setMaxWidth(Region.USE_PREF_SIZE);
        typing.setAccessibleText("AI is thinking");
        Duration cycle = Duration.millis(1200);
        for (int i = 0; i < 3; i++) {
            Region dot = new Region();
            dot.getStyleClass().add("ai-chat-typing-dot");
            dot.setOpacity(0.4);
            typing.getChildren().add(dot);
            Duration start = Duration.millis(i * 160);
            typingAnimation.getKeyFrames().addAll(
                    new KeyFrame(start, new KeyValue(dot.translateYProperty(), 0), new KeyValue(dot.opacityProperty(), 0.4)),
                    new KeyFrame(start.add(Duration.millis(220)),
                            new KeyValue(dot.translateYProperty(), -4, Interpolator.EASE_OUT),
                            new KeyValue(dot.opacityProperty(), 1)),
                    new KeyFrame(start.add(Duration.millis(440)),
                            new KeyValue(dot.translateYProperty(), 0, Interpolator.EASE_IN),
                            new KeyValue(dot.opacityProperty(), 0.4)));
        }
        typingAnimation.getKeyFrames().add(new KeyFrame(cycle));
        typingAnimation.setCycleCount(javafx.animation.Animation.INDEFINITE);
        VBox.setMargin(typing, new Insets(0, 32, 0, 0));
    }

    boolean isTyping() {
        return typingAnimation.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    void refresh() {
        EditorDocument current = workspace.getActiveDocument();
        document.setText(current == null ? "No project open" : current.getTitle());
        document.setAccessibleText(current == null ? "No project open" : "Project: " + current.getTitle());
        try {
            AiSettings selected = settings.get();
            provider.setText(String.valueOf(selected.provider()));
            model.setText(selected.model());
        } catch (RuntimeException ex) {
            provider.setText("AI preferences are unavailable.");
            model.setText("");
        }
        provider.setAccessibleText("Provider: " + provider.getText());
        model.setAccessibleText("Model: " + model.getText());
        modelTip.setText(model.getText());
        model.setVisible(!model.getText().isEmpty());
        Map<AiEdits.Message, AiFeedback> cardByMessage = feedback.getOrDefault(current, Map.of());
        List<Node> entries = new ArrayList<>();
        for (AiEdits.Message message : conversations.getOrDefault(current, List.of())) {
            boolean user = message.role().equals("user");
            Label bubble = new Label(message.text());
            bubble.setWrapText(true);
            bubble.setMaxWidth(Double.MAX_VALUE);
            bubble.getStyleClass().addAll("ai-chat-message", user ? "user" : "assistant");
            bubble.setAccessibleText((user ? "You: " : "AI: ") + message.text());
            AiFeedback card = cardByMessage.get(message);
            if (card == null) {
                VBox.setMargin(bubble, user ? new Insets(0, 0, 0, 32) : new Insets(0, 32, 0, 0));
                entries.add(bubble);
            } else {
                // the controls belong to this reply, so both share one box
                bubble.getStyleClass().add("attached");
                VBox.setMargin(bubble, Insets.EMPTY);
                VBox reply = new VBox(bubble, card);
                reply.getStyleClass().add("ai-chat-reply");
                VBox.setMargin(reply, new Insets(0, 32, 0, 0));
                entries.add(reply);
            }
        }
        boolean waiting = !disposed && active != null && active.document == current;
        if (waiting) entries.add(typing);
        transcript.getChildren().setAll(entries);
        if (waiting) typingAnimation.play(); else typingAnimation.stop();
        placeholder.setVisible(entries.isEmpty());
        conversation.setVvalue(1);
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
        AnimationFile file = target.getFileContent();
        if (!attachments.validateProjectAttachments(file)) return;
        List<AiChatAttachment> submittedAttachments = List.copyOf(attachments.getAttachments());
        List<AiEdits.Message> history = List.copyOf(conversations.getOrDefault(target, List.of()));
        String transcriptPrompt = prompt;
        if (!submittedAttachments.isEmpty()) {
            transcriptPrompt += "\n\nAttachments: " + submittedAttachments.stream()
                    .map(AiChatAttachment::name).collect(java.util.stream.Collectors.joining(", "));
        }
        append(target, "user", transcriptPrompt);
        input.clear();
        String context = selected.provider() + " / " + selected.model();
        activity.accept("AI edit started: " + context);
        CompletableFuture<AiEdits.Result> future;
        try {
            future = request.edit(selected, file, List.copyOf(target.getSelection().layerIds()),
                    target.getPlayhead(), history, prompt, submittedAttachments);
        } catch (RuntimeException ex) {
            activity.accept("AI edit failed: " + context);
            append(target, "assistant", "Could not start the AI request.");
            return;
        }
        attachments.clear();
        Pending pending = new Pending(target, snapshot, future, context);
        active = pending;
        target.animationProperty().addListener(pending.changed);
        refresh();
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
                pending.document.execute(Commands.setDocument("AI Edit", ignored -> result.animation()),
                        result.newAssets());
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
        if (workspace.getActiveDocument() != null) {
            workspace.getActiveDocument().fileContentProperty().removeListener(fileChanged);
        }
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
