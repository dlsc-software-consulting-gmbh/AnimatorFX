package com.dlsc.animatorfx.sample;

import com.dlsc.animatorfx.player.AnimationElement;
import com.dlsc.animatorfx.player.AnimationPlayer;
import com.dlsc.animatorfx.player.AnimationView;
import com.dlsc.animatorfx.player.Fit;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

/**
 * Application interaction with named animation layers, without taking ownership of their nodes.
 */
final class LiveElementsDemo extends BorderPane {

    private final AnimationView view = new AnimationView(Samples.load(Samples.LIVE_ELEMENTS));
    private int clicks;

    LiveElementsDemo() {
        view.setId("live-elements-view");
        view.setFit(Fit.CONTAIN);
        view.setCycleCount(AnimationPlayer.INDEFINITE);
        view.setAutoPlay(true);

        // Export names are assigned to layers in the animation, independently of display names.
        AnimationElement button = view.getElement("button");
        AnimationElement label = view.getElement("label");
        AnimationElement sparkle = view.getElement("sparkle");
        button.setOnMouseClicked(event -> {
            clicks++;
            label.setText("Clicked " + clicks + (clicks == 1 ? " time" : " times"));
            button.setFill(clicks % 2 == 1 ? Color.CORAL : Color.MEDIUMSEAGREEN);
        });

        CheckBox showSparkle = new CheckBox("Show sparkle");
        showSparkle.setId("show-sparkle");
        showSparkle.setSelected(true);
        showSparkle.selectedProperty().addListener((observable, old, selected) -> sparkle.setVisible(selected));
        Button reset = new Button("Reset");
        reset.setId("reset-elements");
        reset.setOnAction(event -> {
            clicks = 0;
            showSparkle.setSelected(true);
            button.clearOverrides();
            label.clearOverrides();
            sparkle.clearOverrides();
        });

        StackPane stage = new StackPane(view);
        stage.setStyle("-fx-background-color: #1e1b4b;");
        stage.setPadding(new Insets(20));
        setCenter(stage);
        HBox controls = new HBox(16, new Label("Click the animated circle."), showSparkle, reset);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(12));
        setBottom(controls);
    }

    void dispose() {
        view.dispose();
    }
}
