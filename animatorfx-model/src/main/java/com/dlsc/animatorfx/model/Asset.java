package com.dlsc.animatorfx.model;

import java.util.Objects;

/**
 * A file stored with the document. {@code path} is the entry inside the {@code .afx} archive.
 */
public record Asset(String id, Kind kind, String path, String name) {

    public enum Kind {IMAGE, FONT, AUDIO, VIDEO}

    public Asset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(name, "name");
    }
}
