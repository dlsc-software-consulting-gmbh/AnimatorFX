package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.model.StateMachine;
import javafx.css.PseudoClass;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.QuadCurve;
import javafx.scene.text.Text;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Draws and selects graph elements; document edits remain the responsibility of the view. */
final class StateMachineGraph extends Pane {

    private static final double NODE_WIDTH = 150;
    private static final double NODE_HEIGHT = 62;
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final Consumer<String> selectState;
    private final IntConsumer selectTransition;
    private final BiConsumer<String, Point2D> moveState;
    private double offsetX;
    private double offsetY;

    StateMachineGraph(Consumer<String> selectState, IntConsumer selectTransition,
                      BiConsumer<String, Point2D> moveState) {
        this.selectState = selectState;
        this.selectTransition = selectTransition;
        this.moveState = moveState;
        setId("sm-graph");
        getStyleClass().add("state-machine-graph");
    }

    void render(StateMachine machine, String selectedState, int selectedTransition) {
        getChildren().clear();
        if (machine == null) {
            setMinSize(800, 600);
            return;
        }
        offsetX = 30 - Math.min(0, machine.states().stream().mapToDouble(StateMachine.State::x).min().orElse(0));
        long loops = machine.transitions().stream().filter(t -> t.source().equals(t.target())).count();
        offsetY = 100 + loops * 35 - Math.min(0,
                machine.states().stream().mapToDouble(StateMachine.State::y).min().orElse(0));
        double width = machine.states().stream().mapToDouble(StateMachine.State::x).max().orElse(0) + offsetX + 250;
        double height = machine.states().stream().mapToDouble(StateMachine.State::y).max().orElse(0) + offsetY + 200;
        setMinSize(Math.max(800, width), Math.max(600, height));
        for (int i = 0; i < machine.transitions().size(); i++) {
            getChildren().add(edge(machine, i));
        }
        for (StateMachine.State state : machine.states()) {
            getChildren().add(stateNode(machine, state));
        }
        select(selectedState, selectedTransition);
    }

    void select(String selectedState, int selectedTransition) {
        for (Node node : getChildren()) {
            Object name = node.getProperties().get("state-name");
            if (name != null) {
                node.pseudoClassStateChanged(SELECTED, Objects.equals(name, selectedState));
            }
            Object edge = node.getProperties().get("edge-index");
            if (edge != null) {
                node.pseudoClassStateChanged(SELECTED, Objects.equals(edge, selectedTransition));
            }
        }
    }

    private Node stateNode(StateMachine machine, StateMachine.State state) {
        Label title = new Label((state.name().equals(machine.initialState()) ? "▶ " : "") + state.name());
        Label detail = new Label(state.marker() + (state.loop() ? " · loop" : ""));
        VBox content = new VBox(3, title, detail);
        content.setMouseTransparent(true);
        StackPane node = new StackPane(content);
        node.setId("sm-node-" + state.name());
        node.getProperties().put("state-name", state.name());
        node.getStyleClass().add("state-machine-node");
        node.setPrefSize(NODE_WIDTH, NODE_HEIGHT);
        node.relocate(state.x() + offsetX, state.y() + offsetY);
        double[] drag = new double[4];
        node.setOnMousePressed(e -> {
            selectState.accept(state.name());
            drag[0] = e.getSceneX();
            drag[1] = e.getSceneY();
            drag[2] = node.getLayoutX();
            drag[3] = node.getLayoutY();
            e.consume();
        });
        node.setOnMouseDragged(e -> {
            node.relocate(Math.max(0, drag[2] + e.getSceneX() - drag[0]),
                    Math.max(0, drag[3] + e.getSceneY() - drag[1]));
            e.consume();
        });
        node.setOnMouseReleased(e -> {
            double x = node.getLayoutX() - offsetX;
            double y = node.getLayoutY() - offsetY;
            if (x != state.x() || y != state.y()) {
                moveState.accept(state.name(), new Point2D(x, y));
            }
            e.consume();
        });
        return node;
    }

    private Node edge(StateMachine machine, int index) {
        StateMachine.Transition transition = machine.transitions().get(index);
        StateMachine.State source = machine.states().stream().filter(s -> s.name().equals(transition.source()))
                .findFirst().orElseThrow();
        StateMachine.State destination = machine.states().stream().filter(s -> s.name().equals(transition.target()))
                .findFirst().orElseThrow();
        double sx = source.x() + offsetX + NODE_WIDTH / 2;
        double sy = source.y() + offsetY + NODE_HEIGHT / 2;
        double tx = destination.x() + offsetX + NODE_WIDTH / 2;
        double ty = destination.y() + offsetY + NODE_HEIGHT / 2;
        Group group = new Group();
        group.setId("sm-edge-" + index);
        group.getProperties().put("edge-index", index);
        group.getStyleClass().add("state-machine-edge");
        Text label = new Text(transition.event() == null ? "finished" : transition.event());
        label.getStyleClass().add("state-machine-edge-label");
        if (source.name().equals(destination.name())) {
            long earlier = machine.transitions().subList(0, index).stream()
                    .filter(t -> t.source().equals(source.name()) && t.target().equals(source.name())).count();
            double rise = 100 + earlier * 35;
            CubicCurve curve = new CubicCurve(sx - 30, sy - NODE_HEIGHT / 2, sx - 80, sy - rise,
                    sx + 80, sy - rise, sx + 30, sy - NODE_HEIGHT / 2);
            curve.getStyleClass().add("state-machine-edge-line");
            label.setX(sx - 20);
            label.setY(sy - rise * 0.75);
            group.getChildren().addAll(curve, arrow(sx + 30, sy - NODE_HEIGHT / 2, -0.5, 1), label);
        } else {
            Point2D direction = new Point2D(tx - sx, ty - sy);
            if (direction.magnitude() < 0.001) direction = new Point2D(1, 0);
            direction = direction.normalize();
            double fromX = sx + direction.getX() * 76;
            double fromY = sy + direction.getY() * 32;
            double toX = tx - direction.getX() * 76;
            double toY = ty - direction.getY() * 32;
            long count = machine.transitions().stream().filter(t -> samePair(t, transition)).count();
            long rank = machine.transitions().subList(0, index).stream().filter(t -> samePair(t, transition)).count();
            double bend = (rank - (count - 1) / 2.0) * 80;
            if (source.name().compareTo(destination.name()) > 0) bend = -bend;
            double controlX = (fromX + toX) / 2 - direction.getY() * bend;
            double controlY = (fromY + toY) / 2 + direction.getX() * bend;
            QuadCurve curve = new QuadCurve(fromX, fromY, controlX, controlY, toX, toY);
            curve.getStyleClass().add("state-machine-edge-line");
            label.setX((fromX + 2 * controlX + toX) / 4);
            label.setY((fromY + 2 * controlY + toY) / 4 - 5);
            group.getChildren().addAll(curve, arrow(toX, toY, toX - controlX, toY - controlY), label);
        }
        group.setOnMouseClicked(e -> {
            selectTransition.accept(index);
            e.consume();
        });
        return group;
    }

    private static boolean samePair(StateMachine.Transition a, StateMachine.Transition b) {
        return (a.source().equals(b.source()) && a.target().equals(b.target()))
                || (a.source().equals(b.target()) && a.target().equals(b.source()));
    }

    private Polygon arrow(double x, double y, double dx, double dy) {
        Point2D unit = new Point2D(dx, dy).normalize();
        double bx = x - unit.getX() * 10;
        double by = y - unit.getY() * 10;
        Polygon arrow = new Polygon(x, y, bx - unit.getY() * 5, by + unit.getX() * 5,
                bx + unit.getY() * 5, by - unit.getX() * 5);
        arrow.getStyleClass().add("state-machine-edge-arrow");
        return arrow;
    }
}
