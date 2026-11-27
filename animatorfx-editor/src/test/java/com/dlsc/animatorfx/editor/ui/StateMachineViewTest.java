package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.model.Marker;
import com.dlsc.animatorfx.model.StateMachine;
import com.dlsc.animatorfx.model.io.AnimationIO;
import com.dlsc.animatorfx.player.AnimationPlayer;
import com.dlsc.animatorfx.player.StateMachineController;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMachineViewTest extends EditorUiTest {

    private void open() {
        run(() -> window.menuItem("menu-view-state-machines").fire());
        layout();
    }

    private void fire(String id) {
        run(() -> ((Button) window.getScene().lookup("#" + id)).fire());
        layout();
    }

    @SuppressWarnings("unchecked")
    private void select(String id, String value) {
        run(() -> ((ComboBox<String>) window.getScene().lookup("#" + id)).setValue(value));
    }

    @Test
    void graphCreatesStatesAndTransitionsWithUndo() {
        execute(Commands.setDocument("Markers", animation -> animation.withMarkers(
                List.of(new Marker("idle", 0, 1), new Marker("busy", 1, 2)))));
        open();
        fire("sm-create-machine");
        fire("sm-add-state");
        run(() -> ((TextField) window.getScene().lookup("#sm-state-name")).setText("Busy"));
        select("sm-state-marker", "busy");
        fire("sm-apply-state");

        StateMachine machine = doc.getAnimation().stateMachines().getFirst();
        assertEquals(List.of("State 1", "Busy"), machine.states().stream().map(StateMachine.State::name).toList());
        assertEquals("busy", machine.states().get(1).marker());
        assertNotNull(fx(() -> window.getScene().lookup("#sm-node-Busy")));

        select("sm-transition-target", "State 1");
        run(() -> ((TextField) window.getScene().lookup("#sm-transition-event")).setText("done"));
        fire("sm-add-transition");
        assertEquals(List.of(new StateMachine.Transition("Busy", "State 1", "done")),
                doc.getAnimation().stateMachines().getFirst().transitions());
        assertNotNull(fx(() -> window.getScene().lookup("#sm-edge-0")));

        run(doc::undo);
        assertTrue(doc.getAnimation().stateMachines().getFirst().transitions().isEmpty());
        run(doc::redo);
        assertEquals(1, doc.getAnimation().stateMachines().getFirst().transitions().size());
    }

    @Test
    void missingMarkersAreReportedAndMachineCreationRequiresAMarker() {
        open();
        fire("sm-create-machine");
        assertTrue(doc.getAnimation().stateMachines().isEmpty());
        assertTrue(fx(() -> ((Label) window.getScene().lookup("#sm-message")).getText()).contains("marker"));

        execute(Commands.setDocument("Marker", animation -> animation.withMarkers(List.of(new Marker("a", 0, 1)))));
        fire("sm-create-machine");
        execute(Commands.setDocument("Remove marker", animation -> animation.withMarkers(List.of())));
        assertTrue(fx(() -> ((Label) window.getScene().lookup("#sm-message")).getText()).contains("marker"));
    }

    @Test
    void loopingStateRejectsCompletionTransitionAndCanBecomeInitial() {
        execute(Commands.setDocument("Marker", animation -> animation.withMarkers(List.of(new Marker("a", 0, 1)))));
        open();
        fire("sm-create-machine");
        fire("sm-add-state");
        run(() -> {
            ((CheckBox) window.getScene().lookup("#sm-state-loop")).setSelected(true);
            ((CheckBox) window.getScene().lookup("#sm-state-initial")).setSelected(true);
        });
        fire("sm-apply-state");
        assertEquals("State 2", doc.getAnimation().stateMachines().getFirst().initialState());
        fire("sm-add-transition");
        assertTrue(doc.getAnimation().stateMachines().getFirst().transitions().isEmpty());
        assertTrue(fx(() -> ((Label) window.getScene().lookup("#sm-message")).getText()).contains("Looping"));
    }

    @Test
    void deletingStateRemovesItsTransitionsAndUndoRestoresThem() {
        execute(Commands.setDocument("Marker", animation -> animation.withMarkers(List.of(new Marker("a", 0, 1)))));
        open();
        fire("sm-create-machine");
        fire("sm-add-state");
        select("sm-transition-target", "State 1");
        run(() -> ((TextField) window.getScene().lookup("#sm-transition-event")).setText("go"));
        fire("sm-add-transition");
        StateMachine before = doc.getAnimation().stateMachines().getFirst();
        fire("sm-delete-state");
        assertEquals(1, doc.getAnimation().stateMachines().getFirst().states().size());
        assertTrue(doc.getAnimation().stateMachines().getFirst().transitions().isEmpty());
        run(doc::undo);
        assertEquals(before, doc.getAnimation().stateMachines().getFirst());
    }

    @Test
    void draggingStateIsUndoableAndReturningToCanvasKeepsTheDocument() {
        execute(Commands.setDocument("Marker", animation -> animation.withMarkers(List.of(new Marker("a", 0, 1)))));
        open();
        fire("sm-create-machine");
        StateMachine before = doc.getAnimation().stateMachines().getFirst();
        Node state = fx(() -> window.getScene().lookup(".state-machine-node"));
        assertNotNull(state);
        drag(state, 20, 20, 80, 65);
        StateMachine moved = doc.getAnimation().stateMachines().getFirst();
        assertTrue(moved.states().getFirst().x() > before.states().getFirst().x());
        assertTrue(moved.states().getFirst().y() > before.states().getFirst().y());
        run(doc::undo);
        assertEquals(before, doc.getAnimation().stateMachines().getFirst());
        fire("sm-close");
        assertNotNull(fx(() -> window.getCanvasView().getScene()));
    }

    @Test
    void graphEditingDoesNotDeleteOrDuplicateHiddenCanvasSelection() {
        add(rect("shape", 0, 0, 50, 50));
        select("shape");
        open();
        run(() -> window.getScene().getRoot().requestFocus());
        run(() -> window.menuItem("menu-edit-duplicate").fire());
        assertEquals(1, doc.getAnimation().layers().size());
        run(() -> window.menuItem("menu-edit-delete").fire());
        assertEquals(List.of("shape"), doc.getAnimation().layers().stream().map(layer -> layer.id()).toList());

        fire("sm-close");
        select("shape");
        run(() -> window.menuItem("menu-edit-delete").fire());
        assertTrue(doc.getAnimation().layers().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void overlappingTransitionsCanBeSelectedAndEditedIndividually() {
        StateMachine machine = new StateMachine("button", "idle",
                List.of(new StateMachine.State("idle", "a", false, 50, 80),
                        new StateMachine.State("busy", "a", false, 260, 80)),
                List.of(new StateMachine.Transition("idle", "busy", "start"),
                        new StateMachine.Transition("idle", "busy", "retry")));
        execute(Commands.setDocument("Machine", animation -> animation
                .withMarkers(List.of(new Marker("a", 0, 1))).withStateMachines(List.of(machine))));
        open();
        Node firstEdge = fx(() -> window.getScene().lookup("#sm-edge-0"));
        Node secondEdge = fx(() -> window.getScene().lookup("#sm-edge-1"));
        assertTrue(fx(() -> firstEdge.getBoundsInParent().getMinY()
                != secondEdge.getBoundsInParent().getMinY()));
        run(() -> ((ListView<String>) window.getScene().lookup("#sm-transition-list"))
                .getSelectionModel().select(1));
        run(() -> ((TextField) window.getScene().lookup("#sm-transition-event")).setText("again"));
        fire("sm-apply-transition");
        assertEquals("start", doc.getAnimation().stateMachines().getFirst().transitions().get(0).event());
        assertEquals("again", doc.getAnimation().stateMachines().getFirst().transitions().get(1).event());
        run(doc::undo);
        assertEquals(machine, doc.getAnimation().stateMachines().getFirst());
    }

    @Test
    void renamedStateKeepsTransitionsAndSurvivesSaving() throws Exception {
        StateMachine machine = new StateMachine("button", "idle",
                List.of(new StateMachine.State("idle", "a", false, 50, 80),
                        new StateMachine.State("busy", "a", false, 260, 80)),
                List.of(new StateMachine.Transition("idle", "busy", "start"),
                        new StateMachine.Transition("busy", "idle", null)));
        execute(Commands.setDocument("Machine", animation -> animation
                .withMarkers(List.of(new Marker("a", 0, 1))).withStateMachines(List.of(machine))));
        open();
        run(() -> ((TextField) window.getScene().lookup("#sm-state-name")).setText("ready"));
        fire("sm-apply-state");
        StateMachine renamed = doc.getAnimation().stateMachines().getFirst();
        assertEquals("ready", renamed.initialState());
        assertEquals(List.of(new StateMachine.Transition("ready", "busy", "start"),
                new StateMachine.Transition("busy", "ready", null)), renamed.transitions());
        var path = dir.resolve("machine.afx");
        AnimationIO.write(doc.getFileContent(), path);
        var saved = AnimationIO.read(path);
        assertEquals(List.of(renamed), saved.animation().stateMachines());
        run(() -> {
            AnimationPlayer player = new AnimationPlayer(saved);
            StateMachineController controller = new StateMachineController(player, saved.animation().stateMachines().getFirst());
            try {
                controller.start();
                assertTrue(controller.send("start"));
                assertEquals("busy", controller.getState());
                assertEquals("a", player.getActiveMarker());
            } finally {
                controller.stop();
                player.dispose();
            }
        });
    }
}
