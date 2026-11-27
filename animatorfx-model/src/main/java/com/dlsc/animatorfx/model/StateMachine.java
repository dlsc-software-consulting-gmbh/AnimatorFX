package com.dlsc.animatorfx.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An interactive animation: states play marker ranges and transitions react to named events
 * or, when the event is null, to completion of a non-looping state.
 */
public record StateMachine(String name, String initialState, List<State> states,
                           List<Transition> transitions) {

    public StateMachine {
        requireName(name, "machine name");
        requireName(initialState, "initial state");
        states = List.copyOf(states);
        transitions = List.copyOf(transitions);
        Map<String, State> byName = new HashMap<>();
        for (State state : states) {
            if (byName.put(state.name(), state) != null) {
                throw new IllegalArgumentException("Duplicate state: " + state.name());
            }
        }
        if (!byName.containsKey(initialState)) {
            throw new IllegalArgumentException("Unknown initial state: " + initialState);
        }
        Set<Trigger> triggers = new HashSet<>();
        for (Transition transition : transitions) {
            State source = byName.get(transition.source());
            if (source == null || !byName.containsKey(transition.target())) {
                throw new IllegalArgumentException("Unknown transition state: " + transition);
            }
            if (transition.event() == null && source.loop()) {
                throw new IllegalArgumentException("Looping state cannot have a completion transition: "
                        + source.name());
            }
            if (!triggers.add(new Trigger(transition.source(), transition.event()))) {
                throw new IllegalArgumentException("Duplicate transition trigger: " + transition);
            }
        }
    }

    /**
     * Checks that each referenced marker exists and has a positive, finite duration.
     *
     * @throws IllegalArgumentException if a marker cannot be played
     */
    public void validateMarkers(Animation animation) {
        Objects.requireNonNull(animation, "animation");
        for (State state : states) {
            Marker marker = animation.marker(state.marker()).orElseThrow(
                    () -> new IllegalArgumentException("Unknown marker: " + state.marker()));
            if (!Double.isFinite(marker.start()) || !Double.isFinite(marker.end())
                    || marker.start() < 0 || marker.end() > animation.duration()
                    || marker.end() <= marker.start()) {
                throw new IllegalArgumentException("Invalid marker range: " + state.marker());
            }
        }
    }

    private static void requireName(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + description);
        }
    }

    /** A marker played on entering the state, and its position in the editor graph. */
    public record State(String name, String marker, boolean loop, double x, double y) {
        public State {
            requireName(name, "state name");
            requireName(marker, "marker name");
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("State position must be finite");
            }
        }
    }

    /** A named event transition; a null event means playback completion. */
    public record Transition(String source, String target, String event) {
        public Transition {
            requireName(source, "source state");
            requireName(target, "target state");
            if (event != null) {
                requireName(event, "event");
            }
        }
    }

    private record Trigger(String source, String event) {
    }
}
