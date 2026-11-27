package com.dlsc.animatorfx.editor.ui;

import com.dlsc.gemsfx.PopOver;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;

/** Session history of background generation jobs. */
final class AiGenerationHistory {
    private final AiNotifications notifications;
    private final Theme theme;
    private final PopOver popover;
    private final VBox entries = new VBox(10);
    private final Label empty = new Label("No AI generations yet.");

    AiGenerationHistory(AiNotifications notifications, Theme theme, Runnable newGeneration) {
        this.notifications = notifications;
        this.theme = theme;
        entries.setId("ai-generation-history");
        empty.getStyleClass().add("ai-history-empty");
        entries.getChildren().add(empty);
        ScrollPane scroll = new ScrollPane(entries);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(400);
        scroll.setPrefViewportHeight(400);
        scroll.setMaxHeight(480);
        Label title = new Label("AI Generations");
        title.getStyleClass().add("ai-history-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button create = new Button("New Generation…", new FontIcon(Material2AL.ADD));
        create.setId("ai-history-new");
        create.getStyleClass().add("accent");
        create.setOnAction(event -> {
            hideNow();
            newGeneration.run();
        });
        HBox header = new HBox(10, title, spacer, create);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(10, header, scroll);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("ai-history");
        popover = new PopOver(content);
        popover.setDetachable(false);
        popover.setArrowLocation(PopOver.ArrowLocation.TOP_RIGHT);
        theme.install(popover.getScene());
        notifications.jobs().addListener((javafx.collections.ListChangeListener<AiNotifications.Job>) change -> refresh());
        refresh();
    }

    void show(Node owner) {
        if (popover.isShowing()) {
            popover.hide();
        } else {
            popover.show(owner);
        }
    }

    private void hideNow() {
        popover.hide(Duration.ZERO);
    }

    private void refresh() {
        entries.getChildren().remove(1, entries.getChildren().size());
        empty.setVisible(notifications.jobs().isEmpty());
        empty.setManaged(empty.isVisible());
        if (notifications.jobs().isEmpty()) {
            return;
        }
        for (AiNotifications.Job job : notifications.jobs().reversed()) {
            Label title = new Label(job.title);
            title.getStyleClass().add("ai-history-job-title");
            Label request = new Label(job.description);
            request.setWrapText(true);
            request.getStyleClass().add("ai-history-request");
            String detailsText = job.summary.startsWith("Request: ")
                    ? job.summary.substring(job.summary.indexOf('\n') + 1) : job.summary;
            Label details = new Label(detailsText);
            details.setWrapText(true);
            details.getStyleClass().add("ai-history-details");
            VBox text = new VBox(5, title, request, details);
            text.setMinWidth(0);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(10);
            if (job.preview != null) {
                ImageView image = new ImageView(job.preview);
                image.setId("ai-history-preview");
                image.setFitWidth(100);
                image.setFitHeight(80);
                image.setPreserveRatio(true);
                StackPane frame = new StackPane(image);
                frame.getStyleClass().add("ai-history-preview");
                frame.setMinSize(100, 80);
                frame.setMaxSize(100, 80);
                row.getChildren().add(frame);
            }
            row.getChildren().add(text);
            VBox card = new VBox(8, row);
            card.setId("ai-history-entry");
            card.getStyleClass().add("ai-history-card");
            if (job.state == AiNotifications.State.RUNNING) {
                Button cancel = new Button("Cancel");
                cancel.setId("ai-history-cancel");
                cancel.setOnAction(event -> notifications.cancel(job));
                card.getChildren().add(cancel);
            } else if (job.state == AiNotifications.State.READY) {
                Button open = new Button(job.opened ? "Opened" : "Open Animation");
                open.setId("ai-history-open");
                open.setDisable(job.opened);
                open.getStyleClass().add("accent");
                open.setOnAction(event -> notifications.open(job));
                card.getChildren().add(open);
            }
            entries.getChildren().add(card);
        }
    }

    void dispose() {
        hideNow();
        theme.uninstall(popover.getScene());
    }
}
