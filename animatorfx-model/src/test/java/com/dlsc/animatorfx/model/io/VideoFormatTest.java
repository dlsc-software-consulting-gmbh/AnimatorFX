package com.dlsc.animatorfx.model.io;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VideoFormatTest {

    private static String document(String members) {
        return """
                {"formatVersion":3,"width":800,"height":600,"duration":3,
                 "assets":[{"id":"clip","kind":"video","path":"assets/clip.mp4"}],
                 "layers":[{"id":"v","name":"Clip","type":"video","asset":"clip",
                            "width":320,"height":180%s}]}
                """.formatted(members.isEmpty() ? "" : "," + members);
    }

    private static Animation read(String json) throws AnimationFormatException {
        return AnimationJson.fromJson(Json.parse(json));
    }

    @Test
    void readsAndEditsVideoWithVisualAndVolumeProperties() throws Exception {
        Animation animation = read(document(""));
        Layer layer = animation.layers().getFirst();
        assertEquals("VideoLayer", layer.getClass().getSimpleName());
        assertEquals("Clip", layer.name());
        assertEquals(Animatable.of(1.0), LayerProperties.get(layer, "volume"));
        Layer changed = LayerProperties.set(layer, "volume", Animatable.of(0.25));
        changed = LayerProperties.set(changed, "opacity", Animatable.of(0.5));
        Animation edited = animation.withLayers(java.util.List.of(changed));
        assertEquals(edited, AnimationJson.fromJson(AnimationJson.toJson(edited)));
    }

    @Test
    void archivePreservesOriginalVideoBytes() throws Exception {
        Animation animation = read(document("\"clipOffset\":0.5,\"volume\":0.25,\"loop\":true,\"muted\":true"));
        byte[] source = {1, 2, 3, 4};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AnimationIO.write(new AnimationFile(animation, Map.of("clip", source)), out);
        AnimationFile reopened = AnimationIO.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(animation, reopened.animation());
        assertArrayEquals(source, reopened.assets().get("clip"));
    }

    @Test
    void rejectsInvalidDimensionsOffsetVolumeAndReferences() {
        for (String member : new String[]{"\"width\":0", "\"height\":-1", "\"clipOffset\":-0.1",
                "\"volume\":1.1", "\"volume\":-0.1",
                "\"volume\":{\"keyframes\":[{\"t\":0,\"v\":1},{\"t\":1,\"v\":1.1}]}"}) {
            assertThrows(AnimationFormatException.class, () -> read(document(member)), member);
        }
        assertThrows(AnimationFormatException.class,
                () -> read(document("").replace("\"kind\":\"video\"", "\"kind\":\"image\"")));
        assertThrows(AnimationFormatException.class,
                () -> read(document("").replace("\"asset\":\"clip\"", "\"asset\":\"missing\"")));
    }

    @Test
    void stillLoadsOldDocuments() throws Exception {
        for (int version : new int[]{1, 2}) {
            Animation animation = read("""
                    {"formatVersion":%d,"width":100,"height":100,"duration":1}
                    """.formatted(version));
            assertTrue(animation.layers().isEmpty());
            assertEquals(1, animation.duration());
        }
    }
}
