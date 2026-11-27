package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Marker;
import com.dlsc.animatorfx.model.StateMachine;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An editable graph of a document's marker-based state machines. Each completed edit is a
 * document command, so the normal document undo and recovery mechanisms apply.
 */
public final class StateMachineView extends BorderPane {

    private final ObjectProperty<EditorDocument> document = new SimpleObjectProperty<>(this, "document");
    private final ChangeListener<Animation> animationListener = (obs, before, after) -> refresh();
    private final ComboBox<String> machines = combo("sm-machines");
    private final TextField machineNameField = field("sm-machine-name");
    private final ComboBox<String> states = combo("sm-states");
    private final TextField stateNameField = field("sm-state-name");
    private final ComboBox<String> marker = combo("sm-state-marker");
    private final CheckBox loop = new CheckBox("Loop");
    private final CheckBox initial = new CheckBox("Initial state");
    private final ComboBox<String> target = combo("sm-transition-target");
    private final TextField event = field("sm-transition-event");
    private final ListView<String> transitionList = new ListView<>();
    private final Label message = new Label();
    private final StateMachineGraph graph = new StateMachineGraph(this::selectState, this::selectTransition,
            this::moveState);
    private final VBox inspector = new VBox(7);
    private final Button addState = button("sm-add-state", "Add State");
    private final Button deleteMachine = button("sm-delete-machine", "Delete Machine");
    private final Button applyMachine = button("sm-apply-machine", "Rename");
    private final Button applyState = button("sm-apply-state", "Apply State");
    private final Button deleteState = button("sm-delete-state", "Delete State");
    private final Button addTransition = button("sm-add-transition", "Add Transition");
    private final Button replaceTransition = button("sm-apply-transition", "Apply Transition");
    private final Button deleteTransition = button("sm-delete-transition", "Delete Transition");
    private String selectedMachine;
    private String selectedState;
    private int selectedTransition = -1;
    private boolean refreshing;
    private Runnable onClose = () -> {};

    public StateMachineView() {
        setId("state-machine-view");
        getStyleClass().add("state-machine-view");
        Button close = button("sm-close", "Canvas");
        close.setOnAction(e -> onClose.run());
        Button create = button("sm-create-machine", "New Machine");
        HBox toolbar = new HBox(6, close, new Label("Machine"), machines, create, deleteMachine, addState);
        toolbar.setPadding(new Insets(8));
        setTop(toolbar);

        ScrollPane graphScroll = new ScrollPane(graph);
        graphScroll.setPannable(true);
        graphScroll.setFitToWidth(false);
        graphScroll.setFitToHeight(false);
        setCenter(graphScroll);

        loop.setId("sm-state-loop");
        initial.setId("sm-state-initial");
        message.setId("sm-message");
        message.setWrapText(true);
        message.getStyleClass().add("state-machine-message");
        event.setPromptText("Empty = playback finishes");
        transitionList.setId("sm-transition-list");
        transitionList.setPrefHeight(110);
        inspector.setPadding(new Insets(10));
        inspector.setPrefWidth(245);
        inspector.getStyleClass().add("state-machine-inspector");
        inspector.getChildren().addAll(new Label("Machine name"), machineNameField, applyMachine,
                new Separator(), new Label("State"), states, new Label("Name"), stateNameField,
                new Label("Marker"), marker, loop, initial, new HBox(6, applyState, deleteState),
                new Separator(), new Label("Transitions"), transitionList, new Label("From selected state"),
                new Label("Target"), target, new Label("Event"), event,
                addTransition, new HBox(6, replaceTransition, deleteTransition), new Separator(), message);
        ScrollPane inspectorScroll = new ScrollPane(inspector);
        inspectorScroll.setFitToWidth(true);
        inspectorScroll.setPrefWidth(255);
        setRight(inspectorScroll);

        document.addListener((obs, before, after) -> {
            if (before != null) before.animationProperty().removeListener(animationListener);
            if (after != null) after.animationProperty().addListener(animationListener);
            selectedMachine = null;
            selectedState = null;
            selectedTransition = -1;
            refresh();
        });
        machines.setOnAction(e -> {
            if (!refreshing) {
                selectedMachine = machines.getValue();
                selectedState = null;
                selectedTransition = -1;
                refresh();
            }
        });
        states.setOnAction(e -> {
            if (!refreshing && states.getValue() != null) {
                selectState(states.getValue());
            }
        });
        transitionList.getSelectionModel().selectedIndexProperty().addListener((obs, old, selected) -> {
            if (!refreshing && selected.intValue() >= 0) selectTransition(selected.intValue());
        });
        create.setOnAction(e -> createMachine());
        deleteMachine.setOnAction(e -> deleteMachine());
        applyMachine.setOnAction(e -> renameMachine());
        addState.setOnAction(e -> addState());
        applyState.setOnAction(e -> applyState());
        deleteState.setOnAction(e -> deleteState());
        addTransition.setOnAction(e -> addTransition());
        replaceTransition.setOnAction(e -> replaceTransition());
        deleteTransition.setOnAction(e -> deleteTransition());
        refresh();
    }

    /**
     * The document whose machines are edited.
     *
     * @return the document property
     */
    public final ObjectProperty<EditorDocument> documentProperty() {
        return document;
    }

    public final EditorDocument getDocument() {
        return document.get();
    }

    public final void setDocument(EditorDocument value) {
        document.set(value);
    }

    /** Sets the action used to return to the animation canvas. */
    public void setOnClose(Runnable action) {
        onClose = Objects.requireNonNull(action, "action");
    }

    private static ComboBox<String> combo(String id) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setId(id);
        combo.setMaxWidth(Double.MAX_VALUE);
        return combo;
    }

    private static TextField field(String id) {
        TextField field = new TextField();
        field.setId(id);
        return field;
    }

    private static Button button(String id, String text) {
        Button button = new Button(text);
        button.setId(id);
        return button;
    }

    private StateMachine machine() {
        EditorDocument doc = getDocument();
        return doc == null ? null : doc.getAnimation().stateMachines().stream()
                .filter(m -> m.name().equals(selectedMachine)).findFirst().orElse(null);
    }

    private StateMachine.State state(StateMachine machine) {
        return machine == null ? null : machine.states().stream()
                .filter(s -> s.name().equals(selectedState)).findFirst().orElse(null);
    }

    private void refresh() {
        refreshing = true;
        try {
            EditorDocument doc = getDocument();
            List<StateMachine> all = doc == null ? List.of() : doc.getAnimation().stateMachines();
            machines.getItems().setAll(all.stream().map(StateMachine::name).toList());
            if (!machines.getItems().contains(selectedMachine)) {
                selectedMachine = all.isEmpty() ? null : all.getFirst().name();
                selectedState = null;
                selectedTransition = -1;
            }
            machines.setValue(selectedMachine);
            StateMachine machine = machine();
            if (machine != null && state(machine) == null) {
                selectedState = machine.initialState();
                selectedTransition = -1;
            }
            if (machine == null || selectedTransition >= machine.transitions().size()) {
                selectedTransition = -1;
            }
            machineNameField.setText(machine == null ? "" : machine.name());
            machines.setDisable(doc == null || all.isEmpty());
            deleteMachine.setDisable(machine == null);
            applyMachine.setDisable(machine == null);
            addState.setDisable(machine == null);
            machineNameField.setDisable(machine == null);
            graph.render(machine, selectedState, selectedTransition);
            refreshInspector(machine);
            showValidation(machine);
        } finally {
            refreshing = false;
        }
    }

    private void refreshInspector(StateMachine machine) {
        boolean before = refreshing;
        refreshing = true;
        try {
            StateMachine.State selected = state(machine);
            List<String> names = machine == null ? List.of()
                    : machine.states().stream().map(StateMachine.State::name).toList();
            states.getItems().setAll(names);
            states.setValue(selected == null ? null : selected.name());
            stateNameField.setText(selected == null ? "" : selected.name());
            List<String> markerNames = getDocument() == null ? List.of()
                    : getDocument().getAnimation().markers().stream().map(Marker::name).toList();
            marker.getItems().setAll(markerNames);
            if (selected != null && !marker.getItems().contains(selected.marker())) {
                marker.getItems().add(selected.marker());
            }
            marker.setValue(selected == null ? null : selected.marker());
            loop.setSelected(selected != null && selected.loop());
            initial.setSelected(selected != null && selected.name().equals(machine.initialState()));
            target.getItems().setAll(names);
            StateMachine.Transition transition = selectedTransition < 0 || machine == null ? null
                    : machine.transitions().get(selectedTransition);
            transitionList.getItems().setAll(machine == null ? List.of() : machine.transitions().stream()
                    .map(t -> t.source() + " — " + (t.event() == null ? "finished" : t.event())
                            + " → " + t.target()).toList());
            if (transition == null) transitionList.getSelectionModel().clearSelection();
            else transitionList.getSelectionModel().select(selectedTransition);
            transitionList.setDisable(machine == null || machine.transitions().isEmpty());
            target.setValue(transition == null ? (names.isEmpty() ? null : names.getFirst()) : transition.target());
            event.setText(transition == null || transition.event() == null ? "" : transition.event());
            for (Node node : List.of(states, stateNameField, marker, loop, initial, applyState, deleteState,
                    target, event, addTransition)) {
                node.setDisable(selected == null);
            }
            replaceTransition.setDisable(transition == null);
            deleteTransition.setDisable(transition == null);
        } finally {
            refreshing = before;
        }
    }

    private void showValidation(StateMachine machine) {
        if (getDocument() == null) {
            message.setText("Open a document to edit state machines.");
        } else if (machine == null) {
            message.setText("Create a marker on the timeline, then add a state machine.");
        } else {
            try {
                machine.validateMarkers(getDocument().getAnimation());
                message.setText("Select a state or transition to edit it. Drag states to arrange the graph.");
            } catch (IllegalArgumentException ex) {
                message.setText(ex.getMessage());
            }
        }
    }

    private void selectState(String name) {
        selectedState = name;
        selectedTransition = -1;
        refreshInspector(machine());
        graph.select(selectedState, selectedTransition);
    }

    private void selectTransition(int index) {
        StateMachine machine = machine();
        if (machine != null && index >= 0 && index < machine.transitions().size()) {
            selectedTransition = index;
            selectedState = machine.transitions().get(index).source();
            refreshInspector(machine);
            graph.select(selectedState, selectedTransition);
        }
    }

    private void createMachine() {
        EditorDocument doc = getDocument();
        if (doc == null) return;
        if (doc.getAnimation().markers().isEmpty()) {
            message.setText("Create a marker before adding a state machine.");
            return;
        }
        String name = unique("Machine ", doc.getAnimation().stateMachines().stream().map(StateMachine::name).toList());
        StateMachine machine = new StateMachine(name, "State 1",
                List.of(new StateMachine.State("State 1", doc.getAnimation().markers().getFirst().name(), false, 50, 80)),
                List.of());
        selectedMachine = name;
        selectedState = "State 1";
        selectedTransition = -1;
        doc.execute(Commands.setDocument("Add State Machine", animation -> {
            List<StateMachine> all = new ArrayList<>(animation.stateMachines());
            all.add(machine);
            return animation.withStateMachines(all);
        }));
    }

    private void deleteMachine() {
        StateMachine current = machine();
        if (current == null) return;
        getDocument().execute(Commands.setDocument("Delete State Machine", animation ->
                animation.withStateMachines(animation.stateMachines().stream()
                        .filter(m -> !m.name().equals(current.name())).toList())));
    }

    private void renameMachine() {
        StateMachine current = machine();
        if (current == null) return;
        try {
            StateMachine changed = new StateMachine(machineNameField.getText().strip(), current.initialState(),
                    current.states(), current.transitions());
            updateMachine("Rename State Machine", current, changed, selectedState, selectedTransition);
        } catch (IllegalArgumentException ex) {
            message.setText(ex.getMessage());
        }
    }

    private void addState() {
        StateMachine current = machine();
        if (current == null) return;
        List<Marker> markers = getDocument().getAnimation().markers();
        if (markers.isEmpty()) {
            message.setText("Create a marker before adding a state.");
            return;
        }
        String name = unique("State ", current.states().stream().map(StateMachine.State::name).toList());
        int index = current.states().size();
        List<StateMachine.State> all = new ArrayList<>(current.states());
        all.add(new StateMachine.State(name, markers.getFirst().name(), false,
                50 + (index % 4) * 210, 80 + (index / 4) * 130));
        updateMachine("Add State", current,
                new StateMachine(current.name(), current.initialState(), all, current.transitions()), name, -1);
    }

    private void applyState() {
        StateMachine current = machine();
        StateMachine.State selected = state(current);
        if (selected == null) return;
        try {
            String name = stateNameField.getText().strip();
            StateMachine.State updated = new StateMachine.State(name, marker.getValue(), loop.isSelected(),
                    selected.x(), selected.y());
            List<StateMachine.State> all = current.states().stream()
                    .map(s -> s.name().equals(selected.name()) ? updated : s).toList();
            List<StateMachine.Transition> transitions = current.transitions().stream()
                    .map(t -> new StateMachine.Transition(t.source().equals(selected.name()) ? name : t.source(),
                            t.target().equals(selected.name()) ? name : t.target(), t.event())).toList();
            String initialState = initial.isSelected() || current.initialState().equals(selected.name())
                    ? name : current.initialState();
            updateMachine("Edit State", current, new StateMachine(current.name(), initialState, all, transitions),
                    name, selectedTransition);
        } catch (IllegalArgumentException ex) {
            message.setText(ex.getMessage());
        }
    }

    private void deleteState() {
        StateMachine current = machine();
        StateMachine.State selected = state(current);
        if (selected == null) return;
        if (current.states().size() == 1) {
            message.setText("A machine needs a state; delete the machine instead.");
            return;
        }
        List<StateMachine.State> remaining = current.states().stream()
                .filter(s -> !s.name().equals(selected.name())).toList();
        List<StateMachine.Transition> transitions = current.transitions().stream()
                .filter(t -> !t.source().equals(selected.name()) && !t.target().equals(selected.name())).toList();
        String initialState = current.initialState().equals(selected.name())
                ? remaining.getFirst().name() : current.initialState();
        updateMachine("Delete State", current,
                new StateMachine(current.name(), initialState, remaining, transitions), initialState, -1);
    }

    private StateMachine.Transition enteredTransition() {
        String text = event.getText().strip();
        return new StateMachine.Transition(selectedState, target.getValue(), text.isEmpty() ? null : text);
    }

    private void addTransition() {
        changeTransitions(false);
    }

    private void replaceTransition() {
        changeTransitions(true);
    }

    private void changeTransitions(boolean replace) {
        StateMachine current = machine();
        if (current == null || state(current) == null || (replace && selectedTransition < 0)) return;
        try {
            List<StateMachine.Transition> all = new ArrayList<>(current.transitions());
            int index = replace ? selectedTransition : all.size();
            if (replace) all.set(index, enteredTransition()); else all.add(enteredTransition());
            updateMachine(replace ? "Edit Transition" : "Add Transition", current,
                    new StateMachine(current.name(), current.initialState(), current.states(), all), selectedState, index);
        } catch (IllegalArgumentException ex) {
            message.setText(ex.getMessage());
        }
    }

    private void deleteTransition() {
        StateMachine current = machine();
        if (current == null || selectedTransition < 0) return;
        List<StateMachine.Transition> all = new ArrayList<>(current.transitions());
        all.remove(selectedTransition);
        updateMachine("Delete Transition", current,
                new StateMachine(current.name(), current.initialState(), current.states(), all), selectedState, -1);
    }

    private void updateMachine(String action, StateMachine previous, StateMachine updated,
                               String nextState, int nextTransition) {
        EditorDocument doc = getDocument();
        try {
            // Validate duplicate machine names before changing the selection.
            if (!previous.name().equals(updated.name()) && doc.getAnimation().stateMachines().stream()
                    .anyMatch(m -> m.name().equals(updated.name()))) {
                throw new IllegalArgumentException("Duplicate state machine: " + updated.name());
            }
            selectedMachine = updated.name();
            selectedState = nextState;
            selectedTransition = nextTransition;
            doc.execute(Commands.setDocument(action, animation -> {
                List<StateMachine> all = new ArrayList<>(animation.stateMachines());
                int index = all.indexOf(previous);
                if (index >= 0) all.set(index, updated);
                return animation.withStateMachines(all);
            }));
            refresh();
        } catch (IllegalArgumentException ex) {
            message.setText(ex.getMessage());
        }
    }

    private static String unique(String prefix, List<String> names) {
        int n = 1;
        while (names.contains(prefix + n)) n++;
        return prefix + n;
    }

    private void moveState(String name, Point2D position) {
        StateMachine current = machine();
        if (current == null) return;
        StateMachine.State live = current.states().stream().filter(s -> s.name().equals(name))
                .findFirst().orElse(null);
        if (live == null || (position.getX() == live.x() && position.getY() == live.y())) return;
        List<StateMachine.State> moved = current.states().stream()
                .map(s -> s.name().equals(name)
                        ? new StateMachine.State(s.name(), s.marker(), s.loop(), position.getX(), position.getY())
                        : s).toList();
        updateMachine("Move State", current,
                new StateMachine(current.name(), current.initialState(), moved, current.transitions()), name, -1);
    }
}
