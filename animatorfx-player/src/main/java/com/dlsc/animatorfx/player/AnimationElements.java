package com.dlsc.animatorfx.player;

import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.io.AnimationFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class AnimationElements {

    private final Runnable refresh;
    private final Map<String, Layer> exports = new LinkedHashMap<>();
    private final Map<String, AnimationElement> handles = new LinkedHashMap<>();
    private AnimationFile document;

    AnimationElements(Runnable refresh) {
        this.refresh = refresh;
    }

    void setDocument(AnimationFile file) {
        if (document == file) return;
        document = file;
        exports.clear();
        if (file != null) {
            for (Layer layer : Layers.flatten(file.animation())) {
                String name = layer.base().exportName();
                if (!name.isEmpty() && !(layer instanceof AudioLayer)) {
                    exports.put(name, layer);
                }
            }
        }
        for (AnimationElement handle : handles()) {
            handle.setLayer(exports.get(handle.getExportName()));
        }
    }

    AnimationElement get(String name) {
        Objects.requireNonNull(name, "exportName");
        if (document == null) throw new IllegalStateException("No animation is set");
        Layer layer = exports.get(name);
        if (layer == null) throw new IllegalArgumentException("No visual element exported as: " + name);
        AnimationElement handle = handles.computeIfAbsent(name,
                key -> new AnimationElement(key, layer, refresh));
        refresh.run();
        return handle;
    }

    List<AnimationElement> handles() {
        return List.copyOf(handles.values());
    }
}
