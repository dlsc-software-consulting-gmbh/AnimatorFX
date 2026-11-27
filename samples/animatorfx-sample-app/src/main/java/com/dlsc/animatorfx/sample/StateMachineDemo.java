package com.dlsc.animatorfx.sample;

import com.dlsc.animatorfx.model.StateMachine;
import com.dlsc.animatorfx.player.AnimationView;
import com.dlsc.animatorfx.player.Fit;
import com.dlsc.animatorfx.player.StateMachineController;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Drives an authored state machine with named events and a completion transition.
 */
final class StateMachineDemo extends BorderPane {

    private final AnimationView view = new AnimationView(Samples.load(Samples.DOWNLOAD_WORKFLOW));
    private final StateMachineController controller;

    StateMachineDemo() {
        StateMachine machine = view.getAnimation().animation().stateMachines().stream()
                .filter(candidate -> candidate.name().equals("download"))
                .findFirst().orElseThrow();
        controller = new StateMachineController(view.getPlayer(), machine);
        view.setId("state-machine-view");
        view.setFit(Fit.CONTAIN);
        view.setMuted(true);

        Label state = new Label();
        state.setId("machine-state");
        state.textProperty().bind(Bindings.concat("State: ", controller.stateProperty()));
        state.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label transition = new Label("Last transition: none (initial state)");
        transition.setId("machine-transition");
        controller.stateProperty().addListener((observable, before, after) -> {
            if (before != null) {
                machine.transitions().stream()
                        .filter(t -> t.source().equals(before) && t.target().equals(after))
                        .findFirst().ifPresent(t -> transition.setText("Last transition: " + before + " --"
                                + (t.event() == null ? "finished" : t.event()) + "--> " + after));
            }
        });
        Button start = eventButton(machine, "Start", "machine-start", "start");
        Button pause = eventButton(machine, "Pause", "machine-pause", "pause");
        Button resume = eventButton(machine, "Resume", "machine-resume", "resume");
        Button fail = eventButton(machine, "Simulate Failure", "machine-fail", "fail");
        Button retry = eventButton(machine, "Retry", "machine-retry", "retry");
        Button reset = eventButton(machine, "Reset", "machine-reset", "reset");
        HBox buttons = new HBox(10, start, pause, resume, fail, retry, reset);
        buttons.setAlignment(Pos.CENTER);
        Label instructions = new Label("Connecting and downloading advance when their animations finish.\n"
                + "Pause or simulate failure to interrupt; Resume restarts the transfer.");
        instructions.setWrapText(true);
        VBox controls = new VBox(8, state, transition, instructions, buttons);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(12));
        StackPane stage = new StackPane(view);
        stage.setStyle("-fx-background-color: #1e1b4b;");
        stage.setPadding(new Insets(20));
        setCenter(stage);
        setBottom(controls);
        controller.start();
    }

    private Button eventButton(StateMachine machine, String text, String id, String event) {
        Button button = new Button(text);
        button.setId(id);
        button.disableProperty().bind(Bindings.createBooleanBinding(() -> machine.transitions().stream()
                .noneMatch(t -> t.source().equals(controller.getState()) && event.equals(t.event())),
                controller.stateProperty()));
        button.setOnAction(action -> controller.send(event));
        return button;
    }

    void dispose() {
        controller.stop();
        view.dispose();
    }
}
