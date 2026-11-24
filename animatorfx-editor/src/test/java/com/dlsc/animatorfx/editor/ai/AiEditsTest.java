package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.LayerProperties;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.io.Json;
import com.dlsc.animatorfx.model.io.JsonWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiEditsTest {
    private static Animation scene() {
        var shape = new ShapeLayer(LayerBase.create("Ball", 3).withId("ball"),
                new Geometry.Ellipse(Animatable.of(new Vec2(50, 50))),
                Animatable.of(new Paint.Solid(Rgba.BLACK)), null, null);
        return Animation.create("Scene", 800, 600, 3).withLayers(List.of(shape));
    }

    @Test
    void editsExistingKeyframesWithoutReplacingOtherContent() throws Exception {
        Animation before = scene().withAssets(List.of(
                new Asset("asset", Asset.Kind.IMAGE, "/private/image.png", "Image")));
        String response = response(List.of(operation("keyframe", "ball", "transform.position",
                "{\"x\":100,\"y\":200}", 1.0)));
        AiEdits.Result result = AiEdits.parse(response, before);
        assertEquals("Done", result.reply());
        var layer = result.animation().layers().getFirst();
        assertEquals(new Vec2(100, 200), LayerProperties.get(layer, "transform.position").at(1));
        assertEquals(before.layers().getFirst().id(), layer.id());
        assertEquals(before.assets(), result.animation().assets());
        assertEquals(((ShapeLayer) before.layers().getFirst()).geometry(), ((ShapeLayer) layer).geometry());
        assertFalse(LayerProperties.get(before.layers().getFirst(), "transform.position").isAnimated());
    }

    @Test
    void rejectsTheEntireBatchWhenAnyOperationIsInvalid() {
        Animation before = scene();
        String response = response(List.of(
                operation("keyframe", "ball", "opacity", "0.5", 1.0),
                operation("set", "missing", "opacity", "0.2", null)));
        assertThrows(Exception.class, () -> AiEdits.parse(response, before));
        assertFalse(LayerProperties.get(before.layers().getFirst(), "opacity").isAnimated());
    }

    @Test
    void doesNotModifyLockedLayers() {
        Animation before = scene();
        var locked = before.layers().getFirst().withBase(before.layers().getFirst().base().withLocked(true));
        Animation project = before.withLayers(List.of(locked));
        assertThrows(IllegalArgumentException.class, () -> AiEdits.parse(response(List.of(
                operation("set", "ball", "opacity", "0.5", null))), project));
        Animation nested = before.withLayers(List.of(new GroupLayer(
                LayerBase.create("Locked group", 3).withId("group").withLocked(true), before.layers())));
        assertThrows(IllegalArgumentException.class, () -> AiEdits.parse(response(List.of(
                operation("set", "ball", "opacity", "0.5", null))), nested));
    }

    @Test
    void doesNotAddToLockedGroupsOrTheirDescendants() {
        var child = new GroupLayer(LayerBase.create("Child", 3).withId("child"), List.of());
        var group = new GroupLayer(LayerBase.create("Locked group", 3).withId("group").withLocked(true),
                List.of(child));
        Animation project = scene().withLayers(List.of(group));
        for (String parent : List.of("group", "child")) {
            var add = operation("add", null, null, fragment(), null);
            add.put("parent", parent);
            var error = assertThrows(IllegalArgumentException.class, () -> AiEdits.parse(response(List.of(add)), project));
            assertEquals("The target layer is locked.", error.getMessage());
        }
    }

    @Test
    void addsLayersWithFreshIdsAndCanAnimateTheNewLayer() throws Exception {
        var add = operation("add", null, null, fragment(), null);
        Animation before = scene();
        Animation after = AiEdits.parse(response(List.of(add,
                operation("keyframe", "new-ball", "opacity", "0.5", 1.0))), before).animation();
        assertEquals(2, after.layers().size());
        assertEquals(before.layers().getFirst(), after.layers().getFirst());
        assertNotEquals("new-ball", after.layers().getLast().id());
        assertEquals(0.5, LayerProperties.get(after.layers().getLast(), "opacity").at(1));
    }

    @Test
    void providedExampleAddsAnAnimatedLayerEvenWhenExistingLayersAreLocked() throws Exception {
        Animation before = scene();
        var existing = before.layers().getFirst().withBase(before.layers().getFirst().base().withLocked(true));
        before = before.withLayers(List.of(existing));
        Animation after = AiEdits.parse(example(), before).animation();
        assertEquals(2, after.layers().size());
        assertEquals(existing, after.layers().getFirst());
        var added = after.layers().getLast();
        assertInstanceOf(ShapeLayer.class, added);
        assertTrue(added.base().transform().position().isAnimated());
        assertNotEquals(added.base().transform().position().at(0), added.base().transform().position().at(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exampleCanBeExtendedToThirtyAnimatedLayersInOneAdd() throws Exception {
        var response = (Map<String, Object>) Json.parse(example());
        var operation = (Map<String, Object>) ((List<?>) response.get("operations")).getFirst();
        var fragment = (Map<String, Object>) Json.parse((String) operation.get("value"));
        var firstLayer = ((List<?>) fragment.get("layers")).getFirst();
        var layers = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < 30; i++) {
            var layer = (Map<String, Object>) Json.parse(JsonWriter.write(firstLayer));
            layer.put("id", "flake-" + i);
            layers.add(layer);
        }
        fragment.put("layers", layers);
        operation.put("value", JsonWriter.write(fragment));
        Animation after = AiEdits.parse(JsonWriter.write(response), scene()).animation();
        assertEquals(31, after.layers().size());
        assertTrue(after.layers().subList(1, 31).stream()
                .allMatch(layer -> layer.base().transform().position().isAnimated()));
    }

    private static String example() {
        String instructions = AiEdits.instructions();
        String startMarker = "Example edit response:\n";
        String endMarker = "\nEnd example.";
        int start = instructions.indexOf(startMarker);
        assertTrue(start >= 0, "The provider needs a complete example edit response");
        start += startMarker.length();
        int end = instructions.indexOf(endMarker, start);
        assertTrue(end > start, "The example edit response must be delimited");
        return instructions.substring(start, end);
    }

    private static String fragment() {
        return """
                {"version":1,"name":"Addition","width":800,"height":600,"frameRate":60,"duration":3,
                 "background":{"r":0,"g":0,"b":0,"a":0},"layers":[
                 {"id":"new-ball","parentId":null,"name":"New ball","inPoint":0,"outPoint":3,
                  "transform":{"anchor":{"value":{"x":0,"y":0},"keyframes":[]},
                   "position":{"value":{"x":0,"y":0},"keyframes":[]},
                   "scale":{"value":{"x":1,"y":1},"keyframes":[]},
                   "rotation":{"value":0,"keyframes":[]},"skew":{"value":{"x":0,"y":0},"keyframes":[]}},
                  "opacity":{"value":1,"keyframes":[]},"content":{"kind":"ellipse",
                   "size":{"x":20,"y":20},"fill":{"value":{"r":1,"g":0,"b":0,"a":1},"keyframes":[]},
                   "stroke":null}}]}
                """;
    }

    @Test
    void rejectsDuplicateKeysAndInvalidValues() {
        assertThrows(Exception.class, () -> AiEdits.parse("{\"reply\":\"a\",\"reply\":\"b\",\"operations\":[]}", scene()));
        assertThrows(Exception.class, () -> AiEdits.parse(response(List.of(
                operation("set", "ball", "opacity", "3", null))), scene()));
    }

    @Test
    void requestContextContainsProjectAndConversationButNotAssetPaths() throws Exception {
        Animation project = scene().withAssets(List.of(
                new Asset("asset", Asset.Kind.IMAGE, "/private/image.png", "Image")));
        String context = AiEdits.context(project, List.of("ball"), 1.5,
                List.of(new AiEdits.Message("user", "Make it blue"),
                        new AiEdits.Message("assistant", "Done")), "Move it");
        assertTrue(context.contains("Ball"));
        assertTrue(context.contains("ball"));
        assertTrue(context.contains("Make it blue"));
        assertTrue(context.contains("Move it"));
        assertFalse(context.contains("/private/image.png"));
    }

    private static Map<String, Object> operation(String kind, String target, String property, String value, Double time) {
        var operation = new java.util.LinkedHashMap<String, Object>();
        operation.put("kind", kind);
        operation.put("target", target);
        operation.put("parent", null);
        operation.put("property", property);
        operation.put("value", value);
        operation.put("time", time);
        operation.put("easing", time == null ? null : "linear");
        return operation;
    }

    private static String response(List<Map<String, Object>> operations) {
        return JsonWriter.write(Map.of("reply", "Done", "operations", operations));
    }
}
