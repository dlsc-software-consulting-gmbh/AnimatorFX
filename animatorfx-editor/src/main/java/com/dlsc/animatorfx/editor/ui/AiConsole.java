package com.dlsc.animatorfx.editor.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

final class AiConsole extends VBox {
    private static final int MAX_CHARS = 100_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final TextArea output = new TextArea();

    AiConsole() {
        setSpacing(8);
        setPadding(new Insets(10));
        output.setId("ai-console-output");
        output.setEditable(false);
        output.setWrapText(true);
        output.setPromptText("AI activity will appear here.");
        VBox.setVgrow(output, Priority.ALWAYS);
        Button clear = new Button("Clear");
        clear.setId("ai-console-clear");
        clear.setOnAction(event -> output.clear());
        getChildren().addAll(output, clear);
    }

    void installChat(AiChat chat) {
        var activity = new VBox(8);
        activity.setPadding(new Insets(10));
        var nodes = java.util.List.copyOf(getChildren());
        getChildren().clear();
        activity.getChildren().setAll(nodes);
        setPadding(Insets.EMPTY);
        TabPane tabs = new TabPane(new Tab("Chat", chat), new Tab("Activity", activity));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().add(tabs);
    }

    void log(String message) {
        String text = output.getText() + "[" + TIME.format(LocalTime.now()) + "] " + message + "\n";
        if (text.length() > MAX_CHARS) {
            int cut = text.indexOf('\n', text.length() - MAX_CHARS);
            text = cut < 0 ? "" : text.substring(cut + 1);
        }
        output.setText(text);
        output.positionCaret(text.length());
    }
}
