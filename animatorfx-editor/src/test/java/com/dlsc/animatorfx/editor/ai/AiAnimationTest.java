package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.importer.SvgImporter;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.ImageLayer;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.model.io.AnimationFormatException;
import com.dlsc.animatorfx.model.io.AnimationJson;
import com.dlsc.animatorfx.model.io.Json;
import com.dlsc.animatorfx.model.io.JsonWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AiAnimationTest {

    @Test
    void convertsEditableLayersAndPreservesBottomToTopSiblingOrder() throws Exception {
        Map<String, Object> rectangle = layer("rect", null, rect());
        Map<String, Object> ellipse = layer("ellipse", "group",
                Map.of("kind", "ellipse", "size", vec(30, 40), "fill", track(color()),
                        "stroke", stroke()));
        Map<String, Object> path = layer("path", "group", Map.of(
                "kind", "path", "contours", List.of(Map.of("closed", true, "vertices", List.of(
                        vertex(0, 0), vertex(20, 0), vertex(10, 20)))),
                "fill", track(color()), "stroke", stroke()));
        Map<String, Object> text = layer("text", null, Map.of(
                "kind", "text", "text", "Editable", "fontFamily", "System",
                "fontSize", track(24), "bold", true, "italic", false, "alignment", "center",
                "fill", track(color()), "stroke", stroke()));
        var file = parse(document(rectangle, ellipse, layer("group", null, Map.of("kind", "group")), path, text));
        Animation animation = file.animation();

        assertTrue(file.assets().isEmpty());
        assertTrue(animation.assets().isEmpty());
        assertTrue(animation.cues().isEmpty());
        assertEquals(Animation.CURRENT_FORMAT, animation.formatVersion());
        assertEquals(List.of("rect", "group", "text"),
                animation.layers().stream().map(l -> l.base().id()).toList());
        assertInstanceOf(Geometry.Rect.class, ((ShapeLayer) animation.layers().getFirst()).geometry());
        GroupLayer group = (GroupLayer) animation.layers().get(1);
        assertEquals(List.of("ellipse", "path"), group.children().stream().map(l -> l.base().id()).toList());
        assertInstanceOf(Geometry.Ellipse.class, ((ShapeLayer) group.children().getFirst()).geometry());
        var geometry = (Geometry.PathGeometry) ((ShapeLayer) group.children().get(1)).geometry();
        assertEquals(3, geometry.path().at(0).contours().getFirst().vertices().size());
        assertTrue(geometry.path().at(0).contours().getFirst().closed());
        TextLayer label = (TextLayer) animation.layers().getLast();
        assertEquals("Editable", label.text());
        assertEquals(TextLayer.Align.CENTER, label.alignment());
        assertEquals(TextLayer.Weight.BOLD, label.fontWeight());
        assertNull(label.fontAssetId());
        assertEquals(animation, AnimationJson.fromJson(AnimationJson.toJson(animation)));
    }

    @Test
    void convertsTransformsAnimatedColorsAndKeyframeTiming() throws Exception {
        Map<String, Object> layer = layer("moving", null, rect());
        obj(layer.get("transform")).put("position", Map.of("value", vec(0, 0), "keyframes", List.of(
                key(0, vec(10, 20), "linear"), key(2, vec(110, 220), "hold"))));
        obj(layer.get("transform")).put("anchor", track(vec(5, 6)));
        obj(layer.get("transform")).put("scale", track(vec(-2, 3)));
        obj(layer.get("transform")).put("rotation", track(90));
        layer.put("opacity", Map.of("value", 1, "keyframes", List.of(
                key(0, 1, "hold"), key(2, 0, "linear"))));
        obj(layer.get("content")).put("fill", Map.of("value", color(), "keyframes", List.of(
                key(0, color(), "linear"),
                key(2, Map.of("r", 0, "g", 0, "b", 1, "a", 1), "linear"))));
        Layer converted = parse(document(layer)).animation().layers().getFirst();
        assertEquals(new Vec2(60, 120), converted.base().transform().position().at(1));
        assertEquals(new Vec2(5, 6), converted.base().transform().anchor().at(0));
        assertEquals(new Vec2(-2, 3), converted.base().transform().scale().at(0));
        assertEquals(90, converted.base().transform().rotation().at(0));
        assertEquals(1, converted.base().opacity().at(1));
        assertEquals(0, converted.base().opacity().at(2));
        Paint.Solid fill = (Paint.Solid) ((ShapeLayer) converted).fill().at(1);
        assertEquals(new Rgba(0.5, 0, 0.5, 1), fill.color());
        assertEquals(2, converted.base().transform().position().keyframes().size());
        assertNotEquals(converted.base().transform().position().keyframes().get(0).id(),
                converted.base().transform().position().keyframes().get(1).id());
    }

    @Test
    void validatesStructureRangesAndUnsupportedDataWithUsefulPaths() throws Exception {
        List<Consumer<Map<String, Object>>> invalid = List.of(
                d -> d.put("version", 2),
                d -> d.remove("name"),
                d -> d.put("assets", List.of()),
                d -> d.put("width", 0),
                d -> d.put("height", 4097),
                d -> d.put("frameRate", 121),
                d -> d.put("duration", 61),
                d -> d.put("duration", "3"),
                d -> d.put("name", "a".repeat(129)),
                d -> obj(d.get("background")).put("a", 1.1),
                d -> first(d).put("id", ""),
                d -> first(d).put("outPoint", 4),
                d -> first(d).put("inPoint", 2.5),
                d -> first(d).put("opacity", track(1.1)),
                d -> obj(first(d).get("transform")).put("scale", track(vec(101, 1))),
                d -> obj(first(d).get("transform")).put("position", track(vec(32769, 0))),
                d -> obj(first(d).get("content")).put("kind", "image"),
                d -> obj(first(d).get("content")).remove("kind"),
                d -> obj(first(d).get("content")).put("kind", null),
                d -> obj(first(d).get("content")).put("kind", 42),
                d -> obj(first(d).get("content")).put("url", "https://example.com/image.png"),
                d -> obj(first(d).get("content")).put("size", vec(-1, 50)),
                d -> obj(first(d).get("content")).put("stroke", Map.of(
                        "color", track(color()), "width", track(-1))),
                d -> first(d).put("opacity", Map.of("value", 1, "keyframes", List.of(key(4, 1, "linear")))),
                d -> first(d).put("opacity", Map.of("value", 1, "keyframes", List.of(
                        key(1, 0, "linear"), key(0, 1, "linear")))),
                d -> first(d).put("opacity", Map.of("value", 1, "keyframes", List.of(
                        key(1, 0, "linear"), key(1.0000001, 1, "linear")))),
                d -> first(d).put("opacity", Map.of("value", 1, "keyframes", List.of(key(1, 0, "bounce")))),
                d -> obj(first(d).get("content")).put("fill", null));
        for (var mutation : invalid) {
            var document = document(layer("shape", null, rect()));
            mutation.accept(document);
            var error = assertThrows(AnimationFormatException.class, () -> parse(document),
                    () -> JsonWriter.write(document));
            assertFalse(error.getMessage().isBlank());
            assertFalse(error.jsonPath().isBlank(), error.getMessage());
        }
    }

    @Test
    void rejectsMissingNonGroupDuplicateAndCyclicParents() {
        for (var layers : List.of(
                List.of(layer("a", "missing", rect())),
                List.of(layer("a", "b", rect()), layer("b", null, rect())),
                List.of(layer("a", null, rect()), layer("a", null, rect())),
                List.of(layer("a", "a", Map.of("kind", "group"))),
                List.of(layer("a", "b", Map.of("kind", "group")),
                        layer("b", "a", Map.of("kind", "group"))))) {
            assertThrows(AnimationFormatException.class,
                    () -> parse(document(layers)));
        }
    }

    @Test
    void unsupportedContentReportsTheKindRatherThanAnUnrelatedUnionBranch() {
        var layer = layer("unsupported", null, rect());
        obj(layer.get("content")).put("kind", "video");
        var error = assertThrows(AnimationFormatException.class, () -> parse(document(layer)));
        assertEquals("$.layers[0].content.kind", error.jsonPath());
    }

    @Test
    void imageContentPlacesARasterAttachmentAsAnEmbeddedImageLayer() throws Exception {
        var layer = layer("photo", null, Map.of("kind", "image", "asset", "asset2", "size", vec(100, 50)));

        AnimationFile file = AiAnimation.parse(JsonWriter.write(document(layer)),
                List.of(svg(), raster("photo.png", new byte[]{1, 2, 3})));

        ImageLayer image = assertInstanceOf(ImageLayer.class, file.animation().layers().getFirst());
        assertEquals("photo", image.base().id());
        assertEquals(100, image.width());
        assertEquals(50, image.height());
        Asset asset = file.animation().assets().getFirst();
        assertEquals(1, file.animation().assets().size());
        assertEquals(image.assetId(), asset.id());
        assertEquals(Asset.Kind.IMAGE, asset.kind());
        assertEquals("assets/" + asset.id() + ".png", asset.path());
        assertEquals("photo.png", asset.name());
        assertArrayEquals(new byte[]{1, 2, 3}, file.assets().get(asset.id()));
    }

    @Test
    void repeatedReferencesShareOneAsset() throws Exception {
        var one = layer("one", null, Map.of("kind", "image", "asset", "asset1", "size", vec(10, 10)));
        var two = layer("two", null, Map.of("kind", "image", "asset", "asset1", "size", vec(20, 20)));

        AnimationFile file = AiAnimation.parse(JsonWriter.write(document(one, two)),
                List.of(raster("a.gif", new byte[]{7})));

        assertEquals(1, file.animation().assets().size());
        assertEquals(1, file.assets().size());
    }

    @Test
    void vectorContentImportsTheSvgFittedAndRetimedToTheLayer() throws Exception {
        var layer = layer("logo", null, Map.of("kind", "vector", "asset", "asset1", "size", vec(60, 60)));
        layer.put("inPoint", 0.5);
        layer.put("outPoint", 2.5);

        AnimationFile file = AiAnimation.parse(JsonWriter.write(document(layer)), List.of(svg()));

        GroupLayer group = assertInstanceOf(GroupLayer.class, file.animation().layers().getFirst());
        assertEquals("logo", group.base().id());
        GroupLayer fitted = assertInstanceOf(GroupLayer.class, group.children().getFirst());
        assertEquals(1, group.children().size());
        assertEquals(new Vec2(0.5, 0.5), fitted.base().transform().scale().at(0));
        assertEquals(new Vec2(0, 20), fitted.base().transform().position().at(0));
        var all = Layers.flatten(file.animation().withLayers(List.of(fitted)));
        assertTrue(all.stream().anyMatch(ShapeLayer.class::isInstance));
        assertTrue(all.stream().allMatch(l -> l.base().inPoint() == 0.5 && l.base().outPoint() == 2.5));
        assertTrue(file.animation().assets().isEmpty());
    }

    @Test
    void unreferencedAttachmentsAreNotEmbedded() throws Exception {
        AnimationFile file = AiAnimation.parse(JsonWriter.write(document(layer("box", null, rect()))),
                List.of(raster("unused.png", new byte[]{1})));

        assertTrue(file.animation().assets().isEmpty());
        assertTrue(file.assets().isEmpty());
    }

    @Test
    void attachmentReferencesMustExistAndMatchTheirKind() throws Exception {
        var unknown = layer("a", null, Map.of("kind", "image", "asset", "asset9", "size", vec(10, 10)));
        var rasterAsVector = layer("a", null, Map.of("kind", "vector", "asset", "asset1", "size", vec(10, 10)));
        var vectorAsImage = layer("a", null, Map.of("kind", "image", "asset", "asset2", "size", vec(10, 10)));
        List<AiAttachment> attachments = List.of(raster("a.png", new byte[]{1}), svg());

        for (var layer : List.of(unknown, rasterAsVector, vectorAsImage)) {
            var error = assertThrows(AnimationFormatException.class,
                    () -> AiAnimation.parse(JsonWriter.write(document(layer)), attachments));
            assertEquals("$.layers[0].content.asset", error.jsonPath());
        }
        var error = assertThrows(AnimationFormatException.class,
                () -> AiAnimation.parse(JsonWriter.write(document(unknown))));
        assertEquals("$.layers[0].content.asset", error.jsonPath());
    }

    @Test
    void rejectsMalformedDuplicateKeysNonFiniteAndOversizedJson() {
        for (String json : List.of("", "null", "[]", "{}", "{\"version\":1,}", "{\"width\":1e999}",
                "{\"version\":1,\"version\":1}", "{\"version\":1,\"\\u0076ersion\":1}",
                " ".repeat(1_000_001), "[".repeat(40) + "]".repeat(40))) {
            assertThrows(AnimationFormatException.class, () -> AiAnimation.parse(json), json.substring(0, Math.min(80, json.length())));
        }
        assertThrows(AnimationFormatException.class, () -> AiAnimation.parse(null));
    }

    @Test
    void boundsLayersTracksPathsAndGroupDepth() throws Exception {
        var layers = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 129; i++) {
            layers.add(layer("l" + i, null, rect()));
        }
        assertThrows(AnimationFormatException.class, () -> parse(document(layers)));
        var layer = layer("a", null, rect());
        var keys = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 129; i++) {
            keys.add(key(i / 128.0, 1, "linear"));
        }
        layer.put("opacity", Map.of("value", 1, "keyframes", keys));
        assertThrows(AnimationFormatException.class, () -> parse(document(layer)));
        layers.clear();
        for (int i = 0; i < 18; i++) {
            layers.add(layer("g" + i, i == 0 ? null : "g" + (i - 1), Map.of("kind", "group")));
        }
        assertThrows(AnimationFormatException.class, () -> parse(document(layers)));
        var vertices = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 257; i++) {
            vertices.add(vertex(i, 0));
        }
        var path = layer("path", null, Map.of("kind", "path", "contours", List.of(Map.of(
                "closed", false, "vertices", vertices)), "fill", track(color()), "stroke", stroke()));
        assertThrows(AnimationFormatException.class, () -> parse(document(path)));
        assertTrue(parse(document()).animation().layers().isEmpty());
    }

    @Test
    void enforcesTheTotalKeyframeBudgetAcrossOtherwiseValidLayers() throws Exception {
        var keys = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 128; i++) {
            keys.add(key(i / 128.0, 1, "linear"));
        }
        var layers = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 32; i++) {
            var layer = layer("l" + i, null, rect());
            layer.put("opacity", Map.of("value", 1, "keyframes", keys));
            layers.add(layer);
        }
        assertEquals(4096, parse(document(layers)).animation().layers().stream()
                .mapToInt(l -> l.base().opacity().keyframes().size()).sum());
        var extra = layer("extra", null, rect());
        extra.put("opacity", Map.of("value", 1, "keyframes", List.of(key(0, 1, "linear"))));
        layers.add(extra);
        var error = assertThrows(AnimationFormatException.class, () -> parse(document(layers)));
        assertTrue(error.getMessage().contains("4096"), error.getMessage());
    }

    @Test
    void enforcesTheTotalVertexBudgetAndSupportsAbsentStrokes() throws Exception {
        var vertices = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 256; i++) {
            vertices.add(vertex(i, 0));
        }
        var contours = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 16; i++) {
            contours.add(Map.of("closed", false, "vertices", vertices));
        }
        var content = new LinkedHashMap<String, Object>(Map.of(
                "kind", "path", "contours", contours, "fill", track(color())));
        content.put("stroke", null);
        var layers = new ArrayList<>(List.of(layer("a", null, content), layer("b", null, content)));
        String json = compactDocument(layers);
        assertTrue(json.length() < 1_000_000);
        var file = AiAnimation.parse(json);
        assertEquals(2, file.animation().layers().size());
        assertNull(((ShapeLayer) file.animation().layers().getFirst()).stroke());
        var extra = new LinkedHashMap<>(content);
        extra.put("contours", List.of(Map.of("closed", false, "vertices", List.of(vertex(0, 0)))));
        layers.add(layer("extra", null, extra));
        var error = assertThrows(AnimationFormatException.class, () -> AiAnimation.parse(compactDocument(layers)));
        assertTrue(error.getMessage().contains("8192"), error.getMessage());
    }

    @Test
    void schemaIsStrictSelfContainedNonRecursiveAndDescriptive() throws Exception {
        Map<String, Object> schema = AiAnimation.schema();
        assertEquals("object", schema.get("type"));
        Map<String, Object> definitions = obj(schema.get("$defs"));
        inspectSchema(schema, definitions, new ArrayList<>());
        assertEquals(JsonWriter.write(schema), JsonWriter.write(Json.parse(JsonWriter.write(schema))));
    }

    private static void inspectSchema(Map<String, Object> schema, Map<String, Object> definitions,
                                      List<String> ancestors) {
        for (String unsupported : List.of("minimum", "maximum", "minLength", "maxLength", "maxItems")) {
            assertFalse(schema.containsKey(unsupported), "Not portable to Anthropic: " + unsupported);
        }
        if (schema.containsKey("$ref")) {
            String ref = (String) schema.get("$ref");
            assertTrue(ref.startsWith("#/$defs/"));
            assertFalse(ancestors.contains(ref), "recursive schema " + ref);
            var next = new ArrayList<>(ancestors);
            next.add(ref);
            inspectSchema(obj(definitions.get(ref.substring(8))), definitions, next);
            return;
        }
        if ("object".equals(schema.get("type"))) {
            assertEquals(false, schema.get("additionalProperties"));
            Map<String, Object> properties = obj(schema.get("properties"));
            assertEquals(properties.keySet(), new java.util.HashSet<>((List<?>) schema.get("required")));
            properties.values().forEach(p -> inspectSchema(obj(p), definitions, ancestors));
        }
        if (schema.containsKey("items")) {
            assertTrue(((String) schema.get("description")).contains("maxItems"),
                    "Array bounds must remain visible to the model");
            inspectSchema(obj(schema.get("items")), definitions, ancestors);
        }
        if (schema.containsKey("anyOf")) {
            ((List<?>) schema.get("anyOf")).forEach(p -> inspectSchema(obj(p), definitions, ancestors));
        }
        assertTrue(schema.containsKey("description"), schema.toString());
    }

    private static AiAttachment raster(String name, byte[] bytes) {
        return new AiAttachment(name, AiAttachment.Kind.RASTER, name.substring(name.lastIndexOf('.') + 1), bytes,
                200, 100, new byte[]{0}, null);
    }

    private static AiAttachment svg() throws Exception {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"40\">"
                + "<rect width=\"120\" height=\"40\" fill=\"#00f\"/></svg>";
        byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
        AnimationFile file = SvgImporter.read(new ByteArrayInputStream(bytes), "logo.svg").file();
        return new AiAttachment("logo.svg", AiAttachment.Kind.VECTOR, "svg", bytes, 120, 40, new byte[]{0}, file);
    }

    private static AnimationFile parse(Map<String, Object> document)
            throws AnimationFormatException {
        return AiAnimation.parse(JsonWriter.write(document));
    }

    private static String compactDocument(List<Map<String, Object>> layers) {
        // These generated numeric path fixtures contain no whitespace inside strings.
        return JsonWriter.write(document(layers)).replace(" ", "").replace("\n", "");
    }

    @SafeVarargs
    private static Map<String, Object> document(Map<String, Object>... layers) {
        return document(List.of(layers));
    }

    private static Map<String, Object> document(List<Map<String, Object>> layers) {
        return new LinkedHashMap<>(Map.of("version", 1, "name", "Demo", "width", 800, "height", 600,
                "frameRate", 60, "duration", 3, "background", color(), "layers", layers));
    }

    private static Map<String, Object> layer(String id, String parent, Map<String, Object> content) {
        var layer = new LinkedHashMap<String, Object>();
        layer.put("id", id);
        layer.put("parentId", parent);
        layer.put("name", id);
        layer.put("inPoint", 0);
        layer.put("outPoint", 2);
        layer.put("transform", new LinkedHashMap<>(Map.of(
                "position", track(vec(0, 0)), "anchor", track(vec(0, 0)), "scale", track(vec(1, 1)),
                "rotation", track(0), "skew", track(vec(0, 0)))));
        layer.put("opacity", track(1));
        layer.put("content", new LinkedHashMap<>(content));
        return layer;
    }

    private static Map<String, Object> rect() {
        return Map.of("kind", "rectangle", "size", vec(100, 50), "cornerRadius", 5,
                "fill", track(color()), "stroke", stroke());
    }

    private static Map<String, Object> stroke() {
        return Map.of("color", track(color()), "width", track(2));
    }

    private static Map<String, Object> track(Object value) {
        return Map.of("value", value, "keyframes", List.of());
    }

    private static Map<String, Object> key(double time, Object value, String easing) {
        return Map.of("time", time, "value", value, "easing", easing);
    }

    private static Map<String, Object> vertex(double x, double y) {
        return Map.of("point", vec(x, y), "in", vec(0, 0), "out", vec(0, 0));
    }

    private static Map<String, Object> vec(double x, double y) {
        return Map.of("x", x, "y", y);
    }

    private static Map<String, Object> color() {
        return new LinkedHashMap<>(Map.of("r", 1, "g", 0, "b", 0, "a", 1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> first(Map<String, Object> document) {
        return obj(((List<?>) document.get("layers")).getFirst());
    }
}
