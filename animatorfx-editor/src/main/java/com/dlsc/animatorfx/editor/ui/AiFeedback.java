package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiEdits;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Effect;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerProperties;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.PropertyRef;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Vec2;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** In-memory controls for the changes in one accepted AI response. */
final class AiFeedback extends VBox {
    private final EditorDocument document;
    private final Animation baseline;
    private final List<Runnable> refreshers = new ArrayList<>();
    private final ChangeListener<Animation> changed = (obs, old, current) -> refresh();
    private boolean updating;

    AiFeedback(EditorDocument document, AiEdits.Result result) {
        this.document = document;
        this.baseline = result.animation();
        setSpacing(4);
        Label title = new Label(result.reply().isBlank() ? "AI changes" : result.reply());
        title.setWrapText(true);
        getChildren().add(title);
        Map<String, List<AiEdits.Change>> byLayer = new LinkedHashMap<>();
        for (var change : result.changes()) {
            byLayer.computeIfAbsent(change.layerId(), ignored -> new ArrayList<>()).add(change);
        }
        byLayer.forEach((id, changes) -> addLayer(id, changes));
        document.animationProperty().addListener(changed);
    }

    void dispose() {
        document.animationProperty().removeListener(changed);
        refreshers.clear();
    }

    private void refresh() {
        if (updating) return;
        updating = true;
        try {
            for (Runnable refresher : List.copyOf(refreshers)) refresher.run();
        } finally {
            updating = false;
        }
    }

    private void addLayer(String id, List<AiEdits.Change> changes) {
        Layer original = Layers.find(document.getAnimation(), id).orElse(null);
        TitledPane pane = new TitledPane(original == null ? "Layer" : original.name(), new VBox());
        pane.setId("ai-feedback-layer-" + id);
        pane.setExpanded(false);
        getChildren().add(pane);
        boolean[] built = {false};
        pane.expandedProperty().addListener((obs, old, expanded) -> {
            if (expanded && !built[0]) {
                built[0] = true;
                VBox rows = new VBox(5);
                pane.setContent(rows);
                buildLayer(rows, id, changes);
                refresh();
            }
        });
        refreshers.add(() -> {
            Layer current = Layers.find(document.getAnimation(), id).orElse(null);
            pane.setText(current == null ? "Layer unavailable" : current.name() + (unlocked(id) ? "" : " (locked)"));
        });
    }

    private void buildLayer(VBox rows, String id, List<AiEdits.Change> changes) {
        Layer layer = Layers.find(baseline, id).orElse(null);
        if (layer == null) {
            rows.getChildren().add(new Label("Layer unavailable."));
            return;
        }
        boolean all = changes.stream().anyMatch(change -> change.property() == null);
        Set<String> keys = new LinkedHashSet<>();
        if (all) {
            keys.add("layerName");
            keys.add("inPoint");
            keys.add("outPoint");
            if (layer instanceof TextLayer) {
                keys.addAll(List.of("text", "fontFamily", "fontWeight", "italic", "alignment"));
            }
            for (PropertyRef ref : LayerProperties.list(layer)) {
                if (ref.key().startsWith("effects[")) {
                    keys.add(ref.key().substring(0, ref.key().indexOf(']') + 1));
                } else {
                    keys.add(ref.key());
                }
            }
        }
        for (AiEdits.Change change : changes) if (change.property() != null) keys.add(change.property());
        for (String key : keys) {
            if (key.equals("text")) {
                text(rows, id);
            } else if (key.equals("layerName") || key.equals("fontFamily")) {
                stringProperty(rows, id, key);
            } else if (key.equals("inPoint") || key.equals("outPoint")) {
                layerTime(rows, id, key);
            } else if (key.equals("fontWeight") || key.equals("italic") || key.equals("alignment")) {
                textStyle(rows, id, key);
            } else if (key.matches("effects\\[[0-9]+]")) {
                int index = Integer.parseInt(key.substring(8, key.length() - 1));
                ShadowAnchor anchor = new ShadowAnchor(id, index);
                for (String field : List.of("color", "radius", "offsetX", "offsetY", "spread")) {
                    property(rows, id, key + "." + field, null, anchor);
                }
            } else if (key.equals("geometry.path")) {
                rows.getChildren().add(new Label("Edit paths on the canvas."));
            } else {
                List<String> frames = changes.stream()
                        .filter(change -> key.equals(change.property()) && change.keyframeId() != null)
                        .map(AiEdits.Change::keyframeId).distinct().toList();
                if (!all && !frames.isEmpty()) {
                    for (String frame : frames) property(rows, id, key, frame, null);
                } else {
                    property(rows, id, key, null, null);
                }
            }
        }
    }

    private void layerTime(VBox rows, String id, String key) {
        TextField field = new TextField();
        field.setId(key.equals("inPoint") ? "ai-feedback-in-point" : "ai-feedback-out-point");
        Runnable sync = () -> {
            Layer current = Layers.find(document.getAnimation(), id).orElse(null);
            field.setDisable(current == null || !unlocked(id));
            if (current != null && !field.isFocused()) {
                field.setText(Double.toString(key.equals("inPoint")
                        ? current.base().inPoint() : current.base().outPoint()));
            }
        };
        Runnable commit = () -> {
            try {
                Layer current = Layers.find(document.getAnimation(), id).orElse(null);
                double value = Double.parseDouble(field.getText());
                if (current != null && unlocked(id) && Double.isFinite(value)
                        && value >= 0 && value <= document.getAnimation().duration()
                        && (key.equals("inPoint") ? value < current.base().outPoint() && value != current.base().inPoint()
                        : value > current.base().inPoint() && value != current.base().outPoint())) {
                    document.execute(Commands.updateLayer("Adjust AI Layer Timing", id, layer ->
                            layer.withBase(key.equals("inPoint") ? layer.base().withInPoint(value)
                                    : layer.base().withOutPoint(value))));
                }
            } catch (NumberFormatException ignored) {}
            sync.run();
        };
        field.setOnAction(event -> commit.run());
        field.focusedProperty().addListener((obs, old, focused) -> { if (!focused) commit.run(); });
        rows.getChildren().add(row(key.equals("inPoint") ? "In Point" : "Out Point", field));
        refreshers.add(sync);
    }

    private void stringProperty(VBox rows, String id, String key) {
        TextField field = new TextField();
        field.setId(key.equals("fontFamily") ? "ai-feedback-font-family" : "ai-feedback-layer-name");
        Supplier<String> value = () -> {
            Layer layer = Layers.find(document.getAnimation(), id).orElse(null);
            if (!unlocked(id) || layer == null) return null;
            return key.equals("layerName") ? layer.name()
                    : layer instanceof TextLayer text ? text.fontFamily() : null;
        };
        Runnable sync = () -> {
            String current = value.get();
            field.setDisable(current == null);
            if (current != null && !field.isFocused()) field.setText(current);
        };
        Runnable commit = () -> {
            String current = value.get(), next = field.getText();
            if (current != null && !next.isBlank() && next.length() <= 128 && !current.equals(next)) {
                document.execute(Commands.updateLayer("Adjust AI " + key, id, layer ->
                        key.equals("layerName") ? layer.withBase(layer.base().withName(next))
                                : layer instanceof TextLayer text ? text.withFontFamily(next) : layer));
            }
            sync.run();
        };
        field.setOnAction(event -> commit.run());
        field.focusedProperty().addListener((obs, old, focused) -> { if (!focused) commit.run(); });
        rows.getChildren().add(row(key.equals("layerName") ? "Name" : "Font", field));
        refreshers.add(sync);
    }

    private void textStyle(VBox rows, String id, String key) {
        Supplier<TextLayer> current = () -> {
            Layer layer = Layers.find(document.getAnimation(), id).orElse(null);
            return unlocked(id) && layer instanceof TextLayer text ? text : null;
        };
        if (key.equals("italic")) {
            CheckBox italic = new CheckBox();
            refreshers.add(() -> {
                TextLayer text = current.get();
                italic.setDisable(text == null);
                if (text != null) italic.setSelected(text.italic());
            });
            italic.setOnAction(event -> {
                TextLayer text = current.get();
                if (!updating && text != null && text.italic() != italic.isSelected()) {
                    boolean selected = italic.isSelected();
                    document.execute(Commands.updateLayer("Adjust AI Italic", id,
                            layer -> ((TextLayer) layer).withItalic(selected)));
                }
            });
            rows.getChildren().add(row("Italic", italic));
        } else {
            ComboBox<String> choices = new ComboBox<>();
            choices.getItems().addAll(key.equals("fontWeight") ? List.of("NORMAL", "BOLD")
                    : List.of("LEFT", "CENTER", "RIGHT"));
            refreshers.add(() -> {
                TextLayer text = current.get();
                choices.setDisable(text == null);
                if (text != null) choices.setValue(key.equals("fontWeight")
                        ? text.fontWeight().name() : text.alignment().name());
            });
            choices.setOnAction(event -> {
                TextLayer text = current.get();
                if (updating || text == null || choices.getValue() == null) return;
                String value = choices.getValue();
                if (key.equals("fontWeight") && !text.fontWeight().name().equals(value)) {
                    document.execute(Commands.updateLayer("Adjust AI Font Weight", id,
                            layer -> ((TextLayer) layer).withFontWeight(TextLayer.Weight.valueOf(value))));
                } else if (key.equals("alignment") && !text.alignment().name().equals(value)) {
                    document.execute(Commands.updateLayer("Adjust AI Text Alignment", id,
                            layer -> ((TextLayer) layer).withAlignment(TextLayer.Align.valueOf(value))));
                }
            });
            rows.getChildren().add(row(key.equals("fontWeight") ? "Weight" : "Alignment", choices));
        }
    }

    private void text(VBox rows, String id) {
        TextField field = new TextField();
        field.setId("ai-feedback-text");
        Runnable sync = () -> {
            Layer layer = Layers.find(document.getAnimation(), id).orElse(null);
            field.setDisable(!(layer instanceof TextLayer) || !unlocked(id));
            if (layer instanceof TextLayer text && !field.isFocused()) field.setText(text.text());
        };
        Runnable commit = () -> {
            Layer current = Layers.find(document.getAnimation(), id).orElse(null);
            if (!field.isDisabled() && field.getText().length() <= 4096
                    && current instanceof TextLayer text && !text.text().equals(field.getText())) {
                String value = field.getText();
                document.execute(Commands.updateLayer("Adjust AI Text", id,
                        layer -> layer instanceof TextLayer target ? target.withText(value) : layer));
            }
            sync.run();
        };
        field.setOnAction(event -> commit.run());
        field.focusedProperty().addListener((obs, old, focused) -> { if (!focused) commit.run(); });
        rows.getChildren().add(row("Text", field));
        refreshers.add(sync);
    }

    private void property(VBox rows, String id, String key, String frameId, ShadowAnchor anchor) {
        Layer layer = Layers.find(baseline, id).orElse(null);
        PropertyRef ref = layer == null ? null : LayerProperties.list(layer).stream()
                .filter(p -> p.key().equals(key)).findFirst().orElse(null);
        if (ref == null) return;
        if (frameId == null && ref.value().isAnimated()) {
            TitledPane track = new TitledPane(ref.label(), new VBox());
            track.setExpanded(false);
            boolean[] built = {false};
            track.expandedProperty().addListener((obs, old, expanded) -> {
                if (expanded && !built[0]) {
                    built[0] = true;
                    var keys = ref.value().keyframes();
                    VBox content = new VBox(5);
                    VBox frames = new VBox(5);
                    Button previous = new Button("Previous");
                    Button next = new Button("Next");
                    Label pageLabel = new Label();
                    HBox navigation = new HBox(6, previous, pageLabel, next);
                    int[] page = {0};
                    List<Runnable> pageRefreshers = new ArrayList<>();
                    Runnable showPage = () -> {
                        refreshers.removeAll(pageRefreshers);
                        pageRefreshers.clear();
                        frames.getChildren().clear();
                        Set<Runnable> existing = new HashSet<>(refreshers);
                        int start = page[0] * 16;
                        for (var frame : keys.subList(start, Math.min(start + 16, keys.size()))) {
                            property(frames, id, key, frame.id(), anchor);
                        }
                        for (Runnable refresher : refreshers) {
                            if (!existing.contains(refresher)) pageRefreshers.add(refresher);
                        }
                        previous.setDisable(page[0] == 0);
                        next.setDisable(start + 16 >= keys.size());
                        pageLabel.setText((start + 1) + "-" + Math.min(start + 16, keys.size())
                                + " / " + keys.size());
                        refresh();
                    };
                    previous.setOnAction(event -> { if (page[0] > 0) { page[0]--; showPage.run(); } });
                    next.setOnAction(event -> { if ((page[0] + 1) * 16 < keys.size()) { page[0]++; showPage.run(); } });
                    content.getChildren().add(frames);
                    if (keys.size() > 16) content.getChildren().add(navigation);
                    track.setContent(content);
                    showPage.run();
                }
            });
            rows.getChildren().add(track);
            return;
        }
        Supplier<Animatable<?>> currentTrack = () -> {
            if (!unlocked(id) || anchor != null && !anchor.valid()) return null;
            Layer current = Layers.find(document.getAnimation(), id).orElse(null);
            if (current == null) return null;
            return LayerProperties.list(current).stream().filter(p -> p.key().equals(key))
                    .map(PropertyRef::value).findFirst().orElse(null);
        };
        Supplier<Keyframe<?>> currentFrame = () -> {
            Animatable<?> track = currentTrack.get();
            return track == null || frameId == null ? null : track.keyframes().stream()
                    .filter(frame -> frame.id().equals(frameId)).findFirst().orElse(null);
        };
        Supplier<Object> currentValue = () -> {
            Animatable<?> track = currentTrack.get();
            if (track == null) return null;
            if (frameId != null) {
                Keyframe<?> frame = currentFrame.get();
                return frame == null ? null : frame.value();
            }
            return track.isAnimated() ? null : track.at(0);
        };
        Consumer<Object> write = value -> {
            try {
                AiEdits.validateValue(key, value);
                Object previous = currentValue.get();
                if (previous == null || previous.equals(value)) return;
                document.execute(Commands.updateLayer("Adjust AI " + ref.label(), id, current -> {
                    Animatable<?> track = LayerProperties.get(current, key);
                    Animatable<?> next = frameId == null ? Animatable.of(value)
                            : replaceFrame(track, frameId, value, null, null);
                    return LayerProperties.set(current, key, next);
                }));
            } catch (IllegalArgumentException ignored) {
                // Invalid user input leaves the project unchanged.
            }
            refresh();
        };
        String label = ref.label() + (frameId == null ? "" : " keyframe");
        switch (ref.type()) {
            case NUMBER -> number(rows, label, key, currentValue, write);
            case VEC2 -> vector(rows, label, key, currentValue, write);
            case COLOR, PAINT -> color(rows, label, key, currentValue, write, ref.type());
            case PATH -> rows.getChildren().add(new Label("Edit paths on the canvas."));
        }
        if (frameId != null) keyframeControls(rows, id, key, currentFrame, currentTrack, frameId);
    }

    private void number(VBox rows, String label, String key, Supplier<Object> value, Consumer<Object> write) {
        TextField field = new TextField();
        field.setId("ai-feedback-" + key.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("-$", ""));
        Runnable sync = () -> {
            Object current = value.get();
            field.setDisable(!(current instanceof Double));
            if (current instanceof Double n && !field.isFocused()) field.setText(Double.toString(n));
        };
        Runnable commit = () -> {
            try { if (!field.isDisabled()) write.accept(Double.parseDouble(field.getText())); }
            catch (NumberFormatException ignored) {}
            sync.run();
        };
        field.setOnAction(event -> commit.run());
        field.focusedProperty().addListener((obs, old, focused) -> { if (!focused) commit.run(); });
        rows.getChildren().add(row(label, field));
        refreshers.add(sync);
    }

    private void vector(VBox rows, String label, String key, Supplier<Object> value, Consumer<Object> write) {
        TextField x = new TextField();
        TextField y = new TextField();
        String prefix = "ai-feedback-" + key.replaceAll("[^A-Za-z0-9]+", "-");
        x.setId(prefix + "-x");
        y.setId(prefix + "-y");
        x.setPromptText("X");
        y.setPromptText("Y");
        Runnable sync = () -> {
            Object current = value.get();
            x.setDisable(!(current instanceof Vec2));
            y.setDisable(!(current instanceof Vec2));
            if (current instanceof Vec2 v) {
                if (!x.isFocused()) x.setText(Double.toString(v.x()));
                if (!y.isFocused()) y.setText(Double.toString(v.y()));
            }
        };
        Runnable commit = () -> {
            try {
                double xv = Double.parseDouble(x.getText()), yv = Double.parseDouble(y.getText());
                if (!x.isDisabled() && Double.isFinite(xv) && Double.isFinite(yv)) write.accept(new Vec2(xv, yv));
            } catch (NumberFormatException ignored) {}
            sync.run();
        };
        x.setOnAction(event -> commit.run());
        y.setOnAction(event -> commit.run());
        x.focusedProperty().addListener((obs, old, focused) -> { if (!focused) commit.run(); });
        y.focusedProperty().addListener((obs, old, focused) -> { if (!focused) commit.run(); });
        HBox fields = new HBox(4, x, y);
        HBox.setHgrow(x, Priority.ALWAYS);
        HBox.setHgrow(y, Priority.ALWAYS);
        rows.getChildren().add(row(label, fields));
        refreshers.add(sync);
    }

    private void color(VBox rows, String label, String key, Supplier<Object> value, Consumer<Object> write,
                       PropertyRef.ValueType type) {
        ColorPicker picker = new ColorPicker();
        Runnable sync = () -> {
            Object current = value.get();
            Rgba rgba = current instanceof Rgba c ? c : current instanceof Paint.Solid solid ? solid.color() : null;
            picker.setDisable(rgba == null);
            if (rgba != null) picker.setValue(new Color(rgba.r(), rgba.g(), rgba.b(), rgba.a()));
        };
        picker.setOnAction(event -> {
            if (!updating && !picker.isDisabled()) {
                Color c = picker.getValue();
                Rgba rgba = new Rgba(c.getRed(), c.getGreen(), c.getBlue(), c.getOpacity());
                write.accept(type == PropertyRef.ValueType.PAINT ? new Paint.Solid(rgba) : rgba);
            }
        });
        rows.getChildren().add(row(label, picker));
        refreshers.add(sync);
    }

    private void keyframeControls(VBox rows, String id, String key, Supplier<Keyframe<?>> frame,
                                  Supplier<Animatable<?>> track, String frameId) {
        TextField time = new TextField();
        time.setId("ai-feedback-keyframe-time");
        ComboBox<String> easing = new ComboBox<>();
        easing.setId("ai-feedback-keyframe-easing");
        easing.getItems().addAll("Linear", "Hold", "Custom");
        Runnable sync = () -> {
            Keyframe<?> current = frame.get();
            time.setDisable(current == null);
            easing.setDisable(current == null);
            if (current != null) {
                if (!time.isFocused()) time.setText(Double.toString(current.time()));
                easing.setValue(current.easing() instanceof Easing.Linear ? "Linear"
                        : current.easing() instanceof Easing.Hold ? "Hold" : "Custom");
            }
        };
        Runnable commitTime = () -> {
            try {
                double value = Double.parseDouble(time.getText());
                Animatable<?> current = track.get();
                if (frame.get() != null && value != frame.get().time() && Double.isFinite(value) && value >= 0
                        && value <= document.getAnimation().duration()
                        && current.keyframes().stream().noneMatch(k -> !k.id().equals(frameId)
                        && Math.abs(k.time() - value) < Animatable.TIME_EPSILON)) {
                    document.execute(Commands.updateLayer("Adjust AI Keyframe Time", id, layer ->
                            LayerProperties.set(layer, key, replaceFrame(LayerProperties.get(layer, key),
                                    frameId, null, value, null))));
                }
            } catch (NumberFormatException ignored) {}
            sync.run();
        };
        time.setOnAction(event -> commitTime.run());
        time.focusedProperty().addListener((obs, old, focused) -> { if (!focused) commitTime.run(); });
        easing.setOnAction(event -> {
            if (!updating && frame.get() != null && !"Custom".equals(easing.getValue())) {
                Easing value = "Hold".equals(easing.getValue()) ? Easing.HOLD : Easing.LINEAR;
                if (value.equals(frame.get().easing())) return;
                document.execute(Commands.updateLayer("Adjust AI Keyframe Easing", id, layer ->
                        LayerProperties.set(layer, key, replaceFrame(LayerProperties.get(layer, key),
                                frameId, null, null, value))));
            }
        });
        rows.getChildren().addAll(row("Time", time), row("Easing", easing));
        refreshers.add(sync);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Animatable<?> replaceFrame(Animatable<?> track, String id, Object value, Double time, Easing easing) {
        List<Keyframe<Object>> frames = new ArrayList<>();
        for (Keyframe<?> frame : track.keyframes()) {
            frames.add(frame.id().equals(id)
                    ? new Keyframe<>(id, time == null ? frame.time() : time,
                    value == null ? frame.value() : value, easing == null ? frame.easing() : easing)
                    : (Keyframe) frame);
        }
        return Animatable.keyframed(frames);
    }

    private boolean unlocked(String id) {
        Layer layer = Layers.find(document.getAnimation(), id).orElse(null);
        while (layer != null) {
            if (layer.base().locked()) return false;
            layer = Layers.parentOf(document.getAnimation(), layer.id()).orElse(null);
        }
        return Layers.find(document.getAnimation(), id).isPresent();
    }

    private HBox row(String label, javafx.scene.Node field) {
        Label name = new Label(label);
        name.setMinWidth(90);
        HBox row = new HBox(6, name, field);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    private final class ShadowAnchor {
        private final String id;
        private final int index;
        private Effect.DropShadow last;

        ShadowAnchor(String id, int index) {
            this.id = id;
            this.index = index;
            Layer layer = Layers.find(baseline, id).orElse(null);
            if (layer != null && index < layer.base().effects().size()
                    && layer.base().effects().get(index) instanceof Effect.DropShadow shadow) last = shadow;
        }

        boolean valid() {
            Layer layer = Layers.find(document.getAnimation(), id).orElse(null);
            if (last == null || layer == null || index >= layer.base().effects().size()
                    || !(layer.base().effects().get(index) instanceof Effect.DropShadow current)) return false;
            for (int i = 0; i < layer.base().effects().size(); i++) {
                if (i != index && layer.base().effects().get(i) == last) return false;
            }
            boolean same = current == last || current.color() == last.color() || current.radius() == last.radius()
                    || current.offsetX() == last.offsetX() || current.offsetY() == last.offsetY()
                    || current.spread() == last.spread();
            if (same) last = current;
            return same;
        }
    }
}
