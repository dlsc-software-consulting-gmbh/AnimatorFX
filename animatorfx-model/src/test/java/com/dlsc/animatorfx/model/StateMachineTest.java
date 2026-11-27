package com.dlsc.animatorfx.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateMachineTest {

    private final StateMachine.State idle = new StateMachine.State("idle", "idle", true, 0, 0);
    private final StateMachine.State loading = new StateMachine.State("loading", "loading", false, 100, 0);

    @Test
    void acceptsValidEventAndCompletionTransitions() {
        assertDoesNotThrow(() -> new StateMachine("button", "idle", List.of(idle, loading),
                List.of(new StateMachine.Transition("idle", "loading", "click"),
                        new StateMachine.Transition("loading", "idle", null))));
    }

    @Test
    void rejectsUnknownStatesAndAmbiguousTriggers() {
        assertThrows(IllegalArgumentException.class,
                () -> new StateMachine("button", "missing", List.of(idle), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StateMachine("button", "idle", List.of(idle, idle), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StateMachine("button", "idle", List.of(idle),
                        List.of(new StateMachine.Transition("idle", "missing", "click"))));
        assertThrows(IllegalArgumentException.class,
                () -> new StateMachine("button", "idle", List.of(idle, loading),
                        List.of(new StateMachine.Transition("idle", "loading", "click"),
                                new StateMachine.Transition("idle", "idle", "click"))));
    }

    @Test
    void rejectsCompletionTransitionFromLoopingState() {
        assertThrows(IllegalArgumentException.class,
                () -> new StateMachine("button", "idle", List.of(idle, loading),
                        List.of(new StateMachine.Transition("idle", "loading", null))));
    }

    @Test
    void validatesMarkerReferencesAgainstAnimation() {
        StateMachine machine = new StateMachine("button", "idle", List.of(idle, loading), List.of());
        Animation animation = Animation.create("test", 100, 100, 2)
                .withMarkers(List.of(new Marker("idle", 0, 1), new Marker("loading", 1, 2)));
        assertDoesNotThrow(() -> machine.validateMarkers(animation));
        assertThrows(IllegalArgumentException.class,
                () -> machine.validateMarkers(animation.withMarkers(List.of(new Marker("idle", 0, 1)))));
        assertThrows(IllegalArgumentException.class,
                () -> machine.validateMarkers(animation.withMarkers(
                        List.of(new Marker("idle", 0, 1), new Marker("loading", 1, 1)))));
    }
}
