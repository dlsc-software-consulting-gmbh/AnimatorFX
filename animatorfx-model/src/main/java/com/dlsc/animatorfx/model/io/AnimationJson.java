package com.dlsc.animatorfx.model.io;

import com.dlsc.animatorfx.model.Animation;

import java.util.Map;

/**
 * Maps an {@link Animation} to and from the JSON tree of {@code animation.json} (see {@link Json}
 * for the tree's value types).
 */
public final class AnimationJson {

    /**
     * How deep layers may be nested in groups; a top-level layer is at depth 1. The limit keeps
     * the JSON text within the nesting that {@link Json} parses, and writing and reading within
     * the stack of any thread. A document nested deeper cannot be written.
     */
    public static final int MAX_LAYER_DEPTH = 100;

    private AnimationJson() {
    }

    /**
     * The document as a JSON tree in the current format. Members that have their default value
     * are left out.
     */
    public static Map<String, Object> toJson(Animation animation) {
        return AnimationJsonWriter.document(animation);
    }

    /**
     * Reads a document from a JSON tree, migrating older format versions. Missing optional members
     * take their defaults and unknown members are ignored.
     *
     * @throws AnimationFormatException if the tree is not a valid document; it carries the path of
     *                                  the offending element
     */
    public static Animation fromJson(Object json) throws AnimationFormatException {
        Node root = Node.root(json);
        Node version = root.required("formatVersion");
        int from = version.integer();
        if (from > Animation.CURRENT_FORMAT) {
            throw version.error("Format version " + from + " is newer than the supported version "
                    + Animation.CURRENT_FORMAT);
        }
        if (from < 1) {
            throw version.error("Format version must be at least 1 but is " + from);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> current = migrate((Map<String, Object>) json, from);
        Node document = Node.root(current);
        try {
            return new AnimationJsonReader().document(document);
        } catch (IllegalArgumentException | NullPointerException | ClassCastException e) {
            // The reader validates before it builds; this only guards against a check it lacks.
            throw document.error("Invalid document: " + e.getMessage(), e);
        }
    }

    /**
     * Brings a document tree of an older format version to {@link Animation#CURRENT_FORMAT}.
     */
    static Map<String, Object> migrate(Map<String, Object> json, int fromVersion) {
        // Versions 2 (audio) and 3 (video) only add optional types and members.
        return json;
    }
}
