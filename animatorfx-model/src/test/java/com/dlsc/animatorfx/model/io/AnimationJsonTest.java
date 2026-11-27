package com.dlsc.animatorfx.model.io;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.SoundCue;
import com.dlsc.animatorfx.model.StateMachine;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Vec2;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationJsonTest {

    private static final String HEAD = "\"formatVersion\":1,\"width\":100,\"height\":100,\"duration\":2";
    private static final String SHAPE = "\"type\":\"shape\",\"geometry\":{\"type\":\"ellipse\",\"size\":[1,1]}";

    private static Animation parse(String json) throws AnimationFormatException {
        return AnimationJson.fromJson(Json.parse(json));
    }

    /** A document with the given members after the mandatory ones. */
    private static String doc(String members) {
        return "{" + HEAD + (members.isEmpty() ? "" : "," + members) + "}";
    }

    private static String layers(String... layers) {
        return doc("\"layers\":[" + String.join(",", layers) + "]");
    }

    private static void assertRejected(String expectedPath, String json) {
        AnimationFormatException e = assertThrows(AnimationFormatException.class, () -> parse(json), json);
        assertEquals(expectedPath, e.jsonPath(), e.getMessage());
        assertTrue(expectedPath.isEmpty() || e.getMessage().contains(expectedPath), e.getMessage());
    }

    @Test
    void kitchenSinkSurvivesRoundTrip() throws Exception {
        Animation a = KitchenSink.animation();

        assertEquals(a, AnimationJson.fromJson(AnimationJson.toJson(a)));
    }

    @Test
    void stateMachinesSurviveRoundTripAndDocumentEdits() throws Exception {
        StateMachine machine = new StateMachine("button", "idle",
                List.of(new StateMachine.State("idle", "idle", true, 12, 34),
                        new StateMachine.State("busy", "busy", false, 150, 40)),
                List.of(new StateMachine.Transition("idle", "busy", "click"),
                        new StateMachine.Transition("busy", "idle", null)));
        Animation animation = Animation.create("button", 100, 100, 2)
                .withMarkers(List.of(new com.dlsc.animatorfx.model.Marker("idle", 0, 1),
                        new com.dlsc.animatorfx.model.Marker("busy", 1, 2)))
                .withStateMachines(List.of(machine)).withName("renamed").withDuration(3);

        assertEquals(animation, AnimationJson.fromJson(AnimationJson.toJson(animation)));
        assertEquals(List.of(machine), animation.stateMachines());
    }

    @Test
    void oldDocumentsHaveNoStateMachines() throws Exception {
        assertTrue(parse(doc("\"layers\":[]")).stateMachines().isEmpty());
    }

    @Test
    void invalidStateMachinesReportTheirLocation() {
        assertRejected("stateMachines[0]", doc("""
                "layers":[],
                "stateMachines":[{"name":"button","initialState":"idle",
                  "states":[{"name":"idle","marker":""}]}]
                """));
    }

    @Test
    void nestedExportNamesSurviveRoundTripAndLayerEdits() throws Exception {
        Animation animation = parse(layers("""
                {"type":"group","id":"g","exportName":"panel","children":[
                  {"type":"text","id":"t","text":"Hello","exportName":"greeting"}
                ]}
                """));
        GroupLayer group = (GroupLayer) animation.layers().getFirst();
        Layer text = group.children().getFirst();
        animation = animation.withLayers(List.of(group.withChildren(List.of(
                text.withBase(text.base().withName("Renamed").withVisible(false))))));
        String json = JsonWriter.write(AnimationJson.toJson(animation));

        assertTrue(json.contains("\"exportName\": \"panel\""));
        assertTrue(json.contains("\"exportName\": \"greeting\""));
        assertEquals(animation, parse(json));
    }

    @Test
    void exportNamesMustBeUniqueAcrossNestedLayers() {
        assertRejected("layers[1].children[0].exportName", layers(
                "{\"type\":\"text\",\"text\":\"One\",\"exportName\":\"greeting\"}",
                "{\"type\":\"group\",\"children\":[{\"type\":\"text\",\"text\":\"Two\","
                        + "\"exportName\":\"greeting\"}]}"));
    }

    @Test
    void programmaticDocumentsAlsoRejectDuplicateNormalizedExportNames() throws Exception {
        Animation animation = parse(layers(
                "{\"type\":\"text\",\"text\":\"One\",\"exportName\":\"greeting\"}",
                "{\"type\":\"text\",\"text\":\"Two\"}"));
        Layer first = animation.layers().getFirst();
        Layer second = animation.layers().get(1);
        assertThrows(IllegalArgumentException.class, () -> animation.withLayers(List.of(first,
                second.withBase(second.base().withExportName(" greeting ")))));
    }

    @Test
    void kitchenSinkSurvivesRoundTripThroughText() throws Exception {
        Animation a = KitchenSink.animation();
        String text = JsonWriter.write(AnimationJson.toJson(a));

        assertEquals(a, parse(text));
        assertEquals(text, JsonWriter.write(AnimationJson.toJson(parse(text))));
    }

    @Test
    void minimalDocumentLoadsWithDefaults() throws Exception {
        Animation a = parse("{\"formatVersion\":1,\"width\":100,\"height\":100,\"duration\":1,\"layers\":[]}");

        assertEquals(new Animation(Animation.CURRENT_FORMAT, "Untitled", 100, 100, 60, 1, Rgba.TRANSPARENT, List.of(), List.of(), List.of()),
                a);
        assertEquals(List.of(), a.cues());
    }

    @Test
    void layerMembersTakeDefaults() throws Exception {
        Animation a = parse(layers("{" + SHAPE + "}", "{\"type\":\"text\",\"text\":\"Hi\"}",
                "{\"type\":\"group\"}", "{\"type\":\"slot\",\"slotName\":\"s\",\"width\":3,\"height\":4,"
                        + "\"transform\":{\"position\":[7,8]}}"));

        ShapeLayer shape = (ShapeLayer) a.layers().get(0);
        assertFalse(shape.id().isEmpty());
        assertNotEquals(shape.id(), a.layers().get(1).id());
        assertEquals("", shape.name());
        assertTrue(shape.base().visible());
        assertFalse(shape.base().locked());
        assertEquals(0, shape.base().inPoint());
        assertEquals(2, shape.base().outPoint());
        assertEquals(Transform.IDENTITY, shape.base().transform());
        assertEquals(Animatable.of(1.0), shape.base().opacity());
        assertNull(shape.base().mask());
        assertEquals(List.of(), shape.base().effects());
        assertNull(shape.base().motionPath());
        assertEquals(Animatable.of(new Paint.Solid(Rgba.BLACK)), shape.fill());
        assertNull(shape.stroke());
        assertNull(shape.trim());

        TextLayer text = (TextLayer) a.layers().get(1);
        assertEquals(new TextLayer(text.base(), "Hi", "System", null, Animatable.of(12.0), TextLayer.Weight.NORMAL,
                false, TextLayer.Align.LEFT, Animatable.of(new Paint.Solid(Rgba.BLACK)), null), text);

        assertEquals(List.of(), ((GroupLayer) a.layers().get(2)).children());

        SlotLayer slot = (SlotLayer) a.layers().get(3);
        assertEquals(Transform.at(7, 8), slot.base().transform());
        assertEquals(Transform.at(7, 8), slot.restTransform());
    }

    @Test
    void keyframesTakeDefaults() throws Exception {
        Animation a = parse(layers("{" + SHAPE + ",\"opacity\":{\"keyframes\":[{\"t\":1,\"v\":1},{\"t\":0,\"v\":0}]}}"));

        List<Keyframe<Double>> keyframes = a.layers().get(0).base().opacity().keyframes();
        assertEquals(List.of(0.0, 1.0), keyframes.stream().map(Keyframe::time).toList());
        assertEquals(Easing.LINEAR, keyframes.get(0).easing());
        assertFalse(keyframes.get(0).id().isEmpty());
        assertNotEquals(keyframes.get(0).id(), keyframes.get(1).id());
    }

    @Test
    void defaultValuedMembersAreOmittedOnWrite() {
        Animation a = Animation.create("Untitled", 100, 50, 3).withLayers(List.of(
                new ShapeLayer(LayerBase.create("", 3).withId("x"),
                        new Geometry.Rect(Animatable.of(new Vec2(4, 5)), Animatable.of(0.0)),
                        Animatable.of(new Paint.Solid(Rgba.BLACK)), null, null)));

        assertEquals("""
                {
                  "formatVersion": 4,
                  "width": 100,
                  "height": 50,
                  "duration": 3,
                  "layers": [
                    {
                      "type": "shape",
                      "id": "x",
                      "geometry": {
                        "type": "rect",
                        "size": [4, 5]
                      }
                    }
                  ]
                }""", JsonWriter.write(AnimationJson.toJson(a)));
    }

    @Test
    void writesTheCurrentFormatVersion() {
        Map<String, Object> json = AnimationJson.toJson(Animation.create("a", 1, 1, 1).withFormatVersion(0));

        assertEquals((double) Animation.CURRENT_FORMAT, json.get("formatVersion"));
        assertEquals("formatVersion", json.keySet().iterator().next());
    }

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        Animation a = parse(doc("\"future\":{\"x\":[1,2]},\"layers\":[{" + SHAPE + ",\"id\":\"a\",\"blend\":\"add\","
                + "\"transform\":{\"perspective\":3}}]"));

        assertEquals(parse(layers("{" + SHAPE + ",\"id\":\"a\"}")), a);
    }

    @Test
    void badKeyframeTimeReportsItsPath() {
        String position = "\"transform\":{\"position\":{\"keyframes\":[{\"t\":0,\"v\":[0,0]},{\"t\":%s,\"v\":[1,1]}]}}";
        String path = "layers[0].transform.position.keyframes[1].t";

        assertRejected(path, layers("{" + SHAPE + "," + position.formatted("-1") + "}"));
        assertRejected(path, layers("{" + SHAPE + "," + position.formatted("\"soon\"") + "}"));
        assertRejected(path, layers("{" + SHAPE + "," + position.formatted("null") + "}"));
    }

    @Test
    void newerFormatVersionIsRejected() {
        assertRejected("formatVersion", "{\"formatVersion\":" + (Animation.CURRENT_FORMAT + 1)
                + ",\"width\":1,\"height\":1,\"duration\":1}");
        assertRejected("formatVersion", "{\"formatVersion\":0,\"width\":1,\"height\":1,\"duration\":1}");
        assertRejected("formatVersion", "{\"formatVersion\":1.5,\"width\":1,\"height\":1,\"duration\":1}");
        assertRejected("formatVersion", "{\"formatVersion\":\"1\",\"width\":1,\"height\":1,\"duration\":1}");
    }

    @Test
    void migrationOfTheCurrentVersionIsTheIdentity() {
        Map<String, Object> json = AnimationJson.toJson(KitchenSink.animation());

        assertSame(json, AnimationJson.migrate(json, Animation.CURRENT_FORMAT));
    }

    @Test
    void requiredMembersMustBePresent() {
        assertRejected("", "[]");
        assertRejected("", "null");
        assertRejected("formatVersion", "{\"width\":1,\"height\":1,\"duration\":1}");
        assertRejected("width", "{\"formatVersion\":1,\"height\":1,\"duration\":1}");
        assertRejected("height", "{\"formatVersion\":1,\"width\":1,\"duration\":1}");
        assertRejected("duration", "{\"formatVersion\":1,\"width\":1,\"height\":1}");
        assertRejected("layers[0].type", layers("{}"));
        assertRejected("layers[0].type", layers("{\"type\":\"unknown\"}"));
        assertRejected("layers[0].geometry", layers("{\"type\":\"shape\"}"));
        assertRejected("layers[0].geometry.type", layers("{\"type\":\"shape\",\"geometry\":{}}"));
        assertRejected("layers[0].geometry.size", layers("{\"type\":\"shape\",\"geometry\":{\"type\":\"rect\"}}"));
        assertRejected("layers[0].text", layers("{\"type\":\"text\"}"));
        assertRejected("layers[0].asset", layers("{\"type\":\"image\",\"width\":1,\"height\":1}"));
        assertRejected("layers[0].slotName", layers("{\"type\":\"slot\",\"width\":1,\"height\":1}"));
        assertRejected("layers[0].height", layers("{\"type\":\"slot\",\"slotName\":\"s\",\"width\":1}"));
        assertRejected("markers[0].end", doc("\"markers\":[{\"name\":\"m\",\"start\":0}]"));
        assertRejected("assets[0].path", doc("\"assets\":[{\"id\":\"a\",\"kind\":\"image\"}]"));
    }

    @Test
    void wrongTypesAreRejectedWithTheirPath() {
        assertRejected("width", "{\"formatVersion\":1,\"width\":\"wide\",\"height\":1,\"duration\":1}");
        assertRejected("name", doc("\"name\":5"));
        assertRejected("background", doc("\"background\":\"blue\""));
        assertRejected("background", doc("\"background\":\"#12345\""));
        assertRejected("layers", doc("\"layers\":{}"));
        assertRejected("layers[0]", layers("5"));
        assertRejected("layers[0].visible", layers("{" + SHAPE + ",\"visible\":\"yes\"}"));
        assertRejected("layers[0].transform", layers("{" + SHAPE + ",\"transform\":[]}"));
        assertRejected("layers[0].transform.scale", layers("{" + SHAPE + ",\"transform\":{\"scale\":[1]}}"));
        assertRejected("layers[0].transform.scale[1]", layers("{" + SHAPE + ",\"transform\":{\"scale\":[1,\"x\"]}}"));
        assertRejected("layers[0].transform.rotation", layers("{" + SHAPE + ",\"transform\":{\"rotation\":[1,2]}}"));
        assertRejected("layers[0].opacity.keyframes", layers("{" + SHAPE + ",\"opacity\":{\"keyframes\":{}}}"));
        assertRejected("layers[0].opacity.keyframes[0].v",
                layers("{" + SHAPE + ",\"opacity\":{\"keyframes\":[{\"t\":0,\"v\":\"x\"}]}}"));
        assertRejected("layers[0].fill.type", layers("{" + SHAPE + ",\"fill\":{\"type\":\"plaid\"}}"));
        assertRejected("layers[0].fill.stops[1].color", layers("{" + SHAPE + ",\"fill\":{\"type\":\"linear\","
                + "\"start\":[0,0],\"end\":[1,1],\"stops\":[{\"offset\":0,\"color\":\"#000000\"},"
                + "{\"offset\":1,\"color\":\"#GG0000\"}]}}"));
        assertRejected("layers[0].stroke.cap", layers("{" + SHAPE + ",\"stroke\":{\"cap\":\"pointy\"}}"));
        assertRejected("layers[0].stroke.dashArray[1]", layers("{" + SHAPE + ",\"stroke\":{\"dashArray\":[1,true]}}"));
        assertRejected("layers[0].effects[1].type",
                layers("{" + SHAPE + ",\"effects\":[{\"type\":\"blur\",\"radius\":1},{\"type\":\"sparkle\"}]}"));
        assertRejected("layers[0].effects[0].radius", layers("{" + SHAPE + ",\"effects\":[{\"type\":\"blur\"}]}"));
        assertRejected("layers[0].mask.mode",
                layers("{" + SHAPE + ",\"mask\":{\"layer\":\"b\",\"mode\":\"CLIP\"}}", "{" + SHAPE + ",\"id\":\"b\"}"));
        assertRejected("layers[0].motionPath.path.contours[0].v[1]", layers("{" + SHAPE
                + ",\"motionPath\":{\"path\":{\"contours\":[{\"v\":[[0,0,0,0,0,0],[1,2,3]]}]},\"progress\":0}}"));
        assertRejected("layers[0].alignment", layers("{\"type\":\"text\",\"text\":\"\",\"alignment\":\"justify\"}"));
        assertRejected("assets[0].kind", doc("\"assets\":[{\"id\":\"a\",\"kind\":\"unknown\",\"path\":\"assets/a\"}]"));
    }

    @Test
    void modelValidationFailuresBecomeFormatExceptions() {
        assertRejected("layers[0].geometry.sides",
                layers("{\"type\":\"shape\",\"geometry\":{\"type\":\"polygon\",\"sides\":2,\"radius\":5}}"));
        assertRejected("layers[0].geometry.sides",
                layers("{\"type\":\"shape\",\"geometry\":{\"type\":\"polygon\",\"sides\":3.5,\"radius\":5}}"));
        assertRejected("layers[0].geometry.points", layers("{\"type\":\"shape\",\"geometry\":{\"type\":\"star\","
                + "\"points\":1,\"outerRadius\":5,\"innerRadius\":2}}"));
        assertRejected("layers[0].opacity.keyframes", layers("{" + SHAPE + ",\"opacity\":{\"keyframes\":[]}}"));
        assertRejected("layers[0].fill.color", layers("{" + SHAPE + ",\"fill\":{\"type\":\"solid\",\"color\":\"red\"}}"));
    }

    @Test
    void nonFiniteNumbersInAHandBuiltTreeAreRejected() {
        Map<String, Object> json = new HashMap<>(Map.of("formatVersion", 1, "width", 100, "height", 100.0,
                "duration", Double.NaN));
        AnimationFormatException e = assertThrows(AnimationFormatException.class, () -> AnimationJson.fromJson(json));
        assertEquals("duration", e.jsonPath());

        json.put("duration", 1);
        json.put("frameRate", Double.POSITIVE_INFINITY);
        e = assertThrows(AnimationFormatException.class, () -> AnimationJson.fromJson(json));
        assertEquals("frameRate", e.jsonPath());

        json.put("frameRate", new Object());
        e = assertThrows(AnimationFormatException.class, () -> AnimationJson.fromJson(json));
        assertEquals("frameRate", e.jsonPath());
    }

    @Test
    void easingIsValidated() {
        String opacity = "{" + SHAPE + ",\"opacity\":{\"keyframes\":[{\"t\":0,\"v\":0,\"e\":%s}]}}";
        String path = "layers[0].opacity.keyframes[0].e";

        assertRejected(path, layers(opacity.formatted("\"bouncy\"")));
        assertRejected(path, layers(opacity.formatted("[0,0,1]")));
        assertRejected(path, layers(opacity.formatted("5")));
        assertRejected(path + "[0]", layers(opacity.formatted("[-0.1,0,1,1]")));
        assertRejected(path + "[2]", layers(opacity.formatted("[0,0,1.5,1]")));
        assertRejected(path + "[1]", layers(opacity.formatted("[0,\"a\",1,1]")));
    }

    @Test
    void easingYMayOvershoot() throws Exception {
        Animation a = parse(layers("{" + SHAPE + ",\"opacity\":{\"keyframes\":[{\"t\":0,\"v\":0,\"e\":[0,-2,1,3]}]}}"));

        assertEquals(new Easing.CubicBezier(0, -2, 1, 3), a.layers().get(0).base().opacity().keyframes().get(0).easing());
    }

    @Test
    void duplicatesAreRejected() {
        assertRejected("layers[1].id", layers("{" + SHAPE + ",\"id\":\"a\"}", "{" + SHAPE + ",\"id\":\"a\"}"));
        assertRejected("layers[1].children[0].id", layers("{" + SHAPE + ",\"id\":\"a\"}",
                "{\"type\":\"group\",\"children\":[{" + SHAPE + ",\"id\":\"a\"}]}"));
        assertRejected("layers[0].opacity.keyframes[1].id", layers("{" + SHAPE
                + ",\"opacity\":{\"keyframes\":[{\"id\":\"k\",\"t\":0,\"v\":0},{\"id\":\"k\",\"t\":1,\"v\":1}]}}"));
        assertRejected("markers[1].name",
                doc("\"markers\":[{\"name\":\"m\",\"start\":0,\"end\":1},{\"name\":\"m\",\"start\":1,\"end\":2}]"));
        String slot = "{\"type\":\"slot\",\"slotName\":\"s\",\"width\":1,\"height\":1}";
        assertRejected("layers[1].children[0].slotName", layers(slot, "{\"type\":\"group\",\"children\":[" + slot + "]}"));
        assertRejected("assets[1].id", doc("\"assets\":[{\"id\":\"a\",\"kind\":\"image\",\"path\":\"assets/a\"},"
                + "{\"id\":\"a\",\"kind\":\"font\",\"path\":\"assets/b\"}]"));
    }

    @Test
    void sameKeyframeIdMayBeUsedByDifferentProperties() throws Exception {
        parse(layers("{" + SHAPE + ",\"opacity\":{\"keyframes\":[{\"id\":\"k\",\"t\":0,\"v\":0}]},"
                + "\"transform\":{\"rotation\":{\"keyframes\":[{\"id\":\"k\",\"t\":0,\"v\":0}]}}}"));
    }

    @Test
    void danglingReferencesAreRejected() {
        String assets = "\"assets\":[{\"id\":\"img\",\"kind\":\"image\",\"path\":\"assets/img.png\"},"
                + "{\"id\":\"font\",\"kind\":\"font\",\"path\":\"assets/font.ttf\"}]";

        assertRejected("layers[0].mask.layer", layers("{" + SHAPE + ",\"mask\":{\"layer\":\"nope\",\"mode\":\"clip\"}}"));
        assertRejected("layers[0].mask.layer",
                layers("{" + SHAPE + ",\"id\":\"a\",\"mask\":{\"layer\":\"a\",\"mode\":\"clip\"}}"));
        assertRejected("layers[1].children[0].mask.layer", layers("{" + SHAPE + ",\"id\":\"outside\"}",
                "{\"type\":\"group\",\"children\":[{" + SHAPE + ",\"mask\":{\"layer\":\"outside\",\"mode\":\"alpha\"}}]}"));
        assertRejected("layers[0].asset",
                doc(assets + ",\"layers\":[{\"type\":\"image\",\"asset\":\"nope\",\"width\":1,\"height\":1}]"));
        assertRejected("layers[0].asset",
                doc(assets + ",\"layers\":[{\"type\":\"image\",\"asset\":\"font\",\"width\":1,\"height\":1}]"));
        assertRejected("layers[0].fontAsset",
                doc(assets + ",\"layers\":[{\"type\":\"text\",\"text\":\"\",\"fontAsset\":\"nope\"}]"));
        assertRejected("layers[0].fontAsset",
                doc(assets + ",\"layers\":[{\"type\":\"text\",\"text\":\"\",\"fontAsset\":\"img\"}]"));
    }

    @Test
    void maskMayReferenceALaterSibling() throws Exception {
        Animation a = parse(layers("{" + SHAPE + ",\"mask\":{\"layer\":\"b\",\"mode\":\"alpha\"}}",
                "{" + SHAPE + ",\"id\":\"b\"}"));

        Layer masked = a.layers().get(0);
        assertEquals("b", masked.base().mask().layerId());
    }

    @Test
    void cornerVerticesMayBeWrittenAsPairs() throws Exception {
        Animation a = parse(layers("{\"type\":\"shape\",\"geometry\":{\"type\":\"path\",\"path\":"
                + "{\"contours\":[{\"closed\":true,\"v\":[[0,0],[10,0],[5,8,-1,0,1,0]]}]}}}"));

        Geometry.PathGeometry geometry = (Geometry.PathGeometry) ((ShapeLayer) a.layers().get(0)).geometry();
        var contour = geometry.path().at(0).contours().get(0);
        assertTrue(contour.closed());
        assertEquals(Vec2.ZERO, contour.vertices().get(1).out());
        assertEquals(new Vec2(1, 0), contour.vertices().get(2).out());
    }

    private static final String AUDIO_ASSETS = "\"assets\":[{\"id\":\"snd\",\"kind\":\"audio\",\"path\":\"assets/snd.wav\"},"
            + "{\"id\":\"img\",\"kind\":\"image\",\"path\":\"assets/img.png\"}]";

    /** A document with an audio and an image asset, one audio layer with the given extra members, and cues. */
    private static String audio(String layerMembers, String... cues) {
        return doc(AUDIO_ASSETS + ",\"layers\":[{\"type\":\"audio\",\"asset\":\"snd\""
                + (layerMembers.isEmpty() ? "" : "," + layerMembers) + "}],\"cues\":[" + String.join(",", cues) + "]");
    }

    @Test
    void versionOneDocumentLoadsAsTheCurrentVersionWithoutCues() throws Exception {
        Animation a = parse("{\"formatVersion\":1,\"name\":\"old\",\"width\":10,\"height\":20,\"duration\":3,"
                + "\"layers\":[{" + SHAPE + ",\"id\":\"a\"}]}");

        assertEquals(Animation.CURRENT_FORMAT, a.formatVersion());
        assertEquals(List.of(), a.cues());
        assertEquals(parse("{\"formatVersion\":2,\"name\":\"old\",\"width\":10,\"height\":20,\"duration\":3,"
                + "\"layers\":[{" + SHAPE + ",\"id\":\"a\"}]}"), a);
    }

    @Test
    void migrationOfVersionOneIsTheIdentity() {
        Map<String, Object> json = new HashMap<>(Map.of("formatVersion", 1.0));

        assertSame(json, AnimationJson.migrate(json, 1));
    }

    @Test
    void audioLayerAndCueTakeDefaults() throws Exception {
        Animation a = parse(audio("", "{\"t\":0.5,\"asset\":\"snd\"}", "{\"t\":0,\"asset\":\"snd\"}"));

        AudioLayer layer = (AudioLayer) a.layers().get(0);
        assertEquals(new AudioLayer(layer.base(), "snd", 0, Animatable.of(1.0), Animatable.of(0.0), false, false),
                layer);
        assertEquals(0, layer.base().inPoint());
        assertEquals(2, layer.base().outPoint());
        SoundCue cue = a.cues().get(0);
        assertEquals(new SoundCue(cue.id(), "", 0.5, "snd", 1, false), cue);
        assertFalse(cue.id().isEmpty());
        assertNotEquals(cue.id(), a.cues().get(1).id());
    }

    @Test
    void minimalAudioLayerAndCueAreWrittenWithoutDefaults() {
        Animation a = Animation.create("Untitled", 100, 50, 3)
                .withAssets(List.of(new Asset("snd", Asset.Kind.AUDIO, "assets/snd.wav", "")))
                .withLayers(List.of(AudioLayer.create("", "snd", 0, 3).withBase(LayerBase.create("", 3).withId("x"))))
                .withCues(List.of(SoundCue.create("", 1.5, "snd").withId("c")));

        assertEquals("""
                {
                  "formatVersion": 4,
                  "width": 100,
                  "height": 50,
                  "duration": 3,
                  "assets": [
                    {
                      "id": "snd",
                      "kind": "audio",
                      "path": "assets/snd.wav"
                    }
                  ],
                  "layers": [
                    {
                      "type": "audio",
                      "id": "x",
                      "asset": "snd"
                    }
                  ],
                  "cues": [
                    {
                      "id": "c",
                      "t": 1.5,
                      "asset": "snd"
                    }
                  ]
                }""", JsonWriter.write(AnimationJson.toJson(a)));
    }

    @Test
    void nonDefaultAudioMembersAreWritten() {
        Animation a = Animation.create("Untitled", 100, 50, 3)
                .withAssets(List.of(new Asset("snd", Asset.Kind.AUDIO, "assets/snd.wav", "")))
                .withLayers(List.of(new AudioLayer(LayerBase.create("Music", 3).withId("x").withInPoint(1), "snd",
                        0.25, Animatable.of(0.5), Animatable.of(-1.0), true, true)))
                .withCues(List.of(new SoundCue("c", "Tick", 0, "snd", 0.75, true)));

        String text = JsonWriter.write(AnimationJson.toJson(a));

        assertTrue(text.contains("""
                      "type": "audio",
                      "id": "x",
                      "name": "Music",
                      "inPoint": 1,
                      "asset": "snd",
                      "clipOffset": 0.25,
                      "volume": 0.5,
                      "balance": -1,
                      "loop": true,
                      "muted": true
                """), text);
        assertTrue(text.contains("""
                      "id": "c",
                      "name": "Tick",
                      "t": 0,
                      "asset": "snd",
                      "volume": 0.75,
                      "muted": true
                """), text);
    }

    @Test
    void audioAssetReferencesAreValidated() {
        assertRejected("layers[0].asset", doc(AUDIO_ASSETS + ",\"layers\":[{\"type\":\"audio\"}]"));
        assertRejected("layers[0].asset", doc(AUDIO_ASSETS + ",\"layers\":[{\"type\":\"audio\",\"asset\":\"img\"}]"));
        assertRejected("layers[0].asset", doc(AUDIO_ASSETS + ",\"layers\":[{\"type\":\"audio\",\"asset\":\"nope\"}]"));
        assertRejected("layers[0].asset",
                doc(AUDIO_ASSETS + ",\"layers\":[{\"type\":\"image\",\"asset\":\"snd\",\"width\":1,\"height\":1}]"));
        assertRejected("layers[0].fontAsset",
                doc(AUDIO_ASSETS + ",\"layers\":[{\"type\":\"text\",\"text\":\"\",\"fontAsset\":\"snd\"}]"));
        assertRejected("cues[0].asset", audio("", "{\"t\":0,\"asset\":\"nope\"}"));
        assertRejected("cues[1].asset", audio("", "{\"t\":0,\"asset\":\"snd\"}", "{\"t\":0,\"asset\":\"img\"}"));
        assertRejected("cues[0].asset", audio("", "{\"t\":0}"));
        assertRejected("cues[0].t", audio("", "{\"asset\":\"snd\"}"));
    }

    @Test
    void audioValuesMustBeInRange() {
        assertRejected("layers[0].clipOffset", audio("\"clipOffset\":-0.1"));
        assertRejected("layers[0].volume", audio("\"volume\":1.5"));
        assertRejected("layers[0].volume", audio("\"volume\":-0.1"));
        assertRejected("layers[0].volume.keyframes[1].v",
                audio("\"volume\":{\"keyframes\":[{\"t\":0,\"v\":1},{\"t\":1,\"v\":1.01}]}"));
        assertRejected("layers[0].balance", audio("\"balance\":-2"));
        assertRejected("layers[0].balance", audio("\"balance\":1.5"));
        assertRejected("layers[0].balance.keyframes[0].v", audio("\"balance\":{\"keyframes\":[{\"t\":0,\"v\":2}]}"));
        assertRejected("cues[0].t", audio("", "{\"t\":-1,\"asset\":\"snd\"}"));
        assertRejected("cues[0].volume", audio("", "{\"t\":0,\"asset\":\"snd\",\"volume\":1.5}"));
        assertRejected("cues[0].volume", audio("", "{\"t\":0,\"asset\":\"snd\",\"volume\":-1}"));
        assertRejected("cues", doc("\"cues\":{}"));
    }

    @Test
    void audioRangeLimitsAreInclusive() throws Exception {
        Animation a = parse(audio("\"clipOffset\":0,\"volume\":{\"keyframes\":[{\"t\":0,\"v\":0},{\"t\":1,\"v\":1}]},"
                + "\"balance\":-1", "{\"t\":0,\"asset\":\"snd\",\"volume\":0}"));

        AudioLayer layer = (AudioLayer) a.layers().get(0);
        assertEquals(0.5, layer.volume().at(0.5));
        assertEquals(-1.0, layer.balance().at(0));
        assertEquals(0, a.cues().get(0).volume());
    }

    @Test
    void nonFiniteAudioNumbersInAHandBuiltTreeAreRejected() {
        Map<String, Object> asset = Map.of("id", "snd", "kind", "audio", "path", "assets/snd.wav");
        Map<String, Object> layer = new HashMap<>(Map.of("type", "audio", "asset", "snd", "clipOffset", Double.NaN));
        Map<String, Object> cue = new HashMap<>(Map.of("t", Double.POSITIVE_INFINITY, "asset", "snd"));
        Map<String, Object> json = new HashMap<>(Map.of("formatVersion", 2, "width", 1, "height", 1, "duration", 1,
                "assets", List.of(asset), "layers", List.of(layer), "cues", List.of(cue)));

        AnimationFormatException e = assertThrows(AnimationFormatException.class, () -> AnimationJson.fromJson(json));
        assertEquals("layers[0].clipOffset", e.jsonPath());

        layer.put("clipOffset", 0);
        e = assertThrows(AnimationFormatException.class, () -> AnimationJson.fromJson(json));
        assertEquals("cues[0].t", e.jsonPath());
    }

    @Test
    void duplicateCueIdsAreRejected() {
        assertRejected("cues[1].id",
                audio("", "{\"id\":\"c\",\"t\":0,\"asset\":\"snd\"}", "{\"id\":\"c\",\"t\":1,\"asset\":\"snd\"}"));
    }

    @Test
    void audioLayerCannotBeAMaskButMayCarryOne() throws Exception {
        String audioLayer = "{\"type\":\"audio\",\"id\":\"sound\",\"asset\":\"snd\"%s}";

        assertRejected("layers[0].mask.layer", doc(AUDIO_ASSETS + ",\"layers\":[{" + SHAPE
                + ",\"mask\":{\"layer\":\"sound\",\"mode\":\"clip\"}}," + audioLayer.formatted("") + "]"));

        Animation a = parse(doc(AUDIO_ASSETS + ",\"layers\":[{" + SHAPE + ",\"id\":\"shape\"},"
                + audioLayer.formatted(",\"mask\":{\"layer\":\"shape\",\"mode\":\"clip\"},\"opacity\":0.5,"
                + "\"transform\":{\"position\":[1,2]}") + "]"));
        LayerBase base = a.layers().get(1).base();
        assertEquals("shape", base.mask().layerId());
        assertEquals(Animatable.of(0.5), base.opacity());
        assertEquals(Transform.at(1, 2), base.transform());
    }
}
