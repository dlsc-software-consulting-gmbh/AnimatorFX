package com.dlsc.animatorfx.editor.session;

import com.dlsc.animatorfx.editor.doc.Selection.KeyframeRef;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the editor restores on its next start.
 *
 * @param window           the window placement, or {@code null} when the window was never placed
 * @param theme            {@code DARK}, {@code LIGHT} or {@code SYSTEM}
 * @param activeDocumentId the id of the active document, or {@code null}
 * @param recentFiles      most recent first, at most {@value #MAX_RECENT_FILES}
 * @param audioMuted       whether the sound of the editor is switched off
 * @param followSelection  whether the timeline scrolls to the layers that get selected
 */
public record SessionState(WindowState window, Layout layout, String theme, List<DocumentState> documents,
                           String activeDocumentId, List<Path> recentFiles, boolean audioMuted,
                           boolean followSelection) {

    public static final int MAX_RECENT_FILES = 20;

    private static final int VERSION = 1;

    /**
     * @param screen the index of the screen the window was on
     */
    public record WindowState(double x, double y, double width, double height, boolean maximized, int screen) {
    }

    /**
     * @param sideDivider     position (0..1) of the divider between the canvas and the side panel
     * @param timelineDivider position (0..1) of the divider between the upper area and the timeline
     * @param sideTab         the id of the active side-panel tab
     */
    public record Layout(double sideDivider, double timelineDivider, boolean sidePanelCollapsed,
                         boolean timelineCollapsed, String sideTab) {

        public static final Layout DEFAULT = new Layout(0.8, 0.66, false, false, "inspector");

        public Layout {
            Objects.requireNonNull(sideTab, "sideTab");
        }
    }

    /**
     * @param path the file of the document, or {@code null} for an untitled document
     */
    public record DocumentState(String id, Path path, double canvasZoom, double canvasPanX, double canvasPanY,
                                double playhead, List<String> selectedLayerIds, List<KeyframeRef> selectedKeyframes,
                                double timelineZoom, double timelineScroll, List<String> expandedLayerIds,
                                boolean autoKey, boolean showAllProperties) {

        public DocumentState {
            Objects.requireNonNull(id, "id");
            selectedLayerIds = List.copyOf(selectedLayerIds);
            selectedKeyframes = List.copyOf(selectedKeyframes);
            expandedLayerIds = List.copyOf(expandedLayerIds);
        }
    }

    public SessionState {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(theme, "theme");
        documents = List.copyOf(documents);
        recentFiles = recentFiles.size() > MAX_RECENT_FILES
                ? List.copyOf(recentFiles.subList(0, MAX_RECENT_FILES)) : List.copyOf(recentFiles);
    }

    /** A state with the sound switched on. */
    public SessionState(WindowState window, Layout layout, String theme, List<DocumentState> documents,
                        String activeDocumentId, List<Path> recentFiles) {
        this(window, layout, theme, documents, activeDocumentId, recentFiles, false);
    }

    /** A state where the timeline follows the selection. */
    public SessionState(WindowState window, Layout layout, String theme, List<DocumentState> documents,
                        String activeDocumentId, List<Path> recentFiles, boolean audioMuted) {
        this(window, layout, theme, documents, activeDocumentId, recentFiles, audioMuted, true);
    }

    public static SessionState defaults() {
        return new SessionState(null, Layout.DEFAULT, "SYSTEM", List.of(), null, List.of(), false, true);
    }

    public SessionState withAudioMuted(boolean muted) {
        return new SessionState(window, layout, theme, documents, activeDocumentId, recentFiles, muted,
                followSelection);
    }

    /**
     * A copy with the file at the front of the recent files.
     */
    public SessionState withRecentFile(Path file) {
        return new SessionState(window, layout, theme, documents, activeDocumentId, addRecent(recentFiles, file),
                audioMuted, followSelection);
    }

    /**
     * The list with the file moved or added to the front, capped at {@value #MAX_RECENT_FILES}.
     */
    public static List<Path> addRecent(List<Path> recentFiles, Path file) {
        List<Path> list = new ArrayList<>(recentFiles);
        list.remove(file);
        list.add(0, file);
        return List.copyOf(list.size() > MAX_RECENT_FILES ? list.subList(0, MAX_RECENT_FILES) : list);
    }

    /**
     * The state as a value tree for {@link com.dlsc.animatorfx.model.io.JsonWriter}.
     */
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", VERSION);
        if (window != null) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("x", finite(window.x(), 0));
            w.put("y", finite(window.y(), 0));
            w.put("width", finite(window.width(), 0));
            w.put("height", finite(window.height(), 0));
            w.put("maximized", window.maximized());
            w.put("screen", window.screen());
            out.put("window", w);
        }
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("sideDivider", finite(layout.sideDivider(), Layout.DEFAULT.sideDivider()));
        l.put("timelineDivider", finite(layout.timelineDivider(), Layout.DEFAULT.timelineDivider()));
        l.put("sidePanelCollapsed", layout.sidePanelCollapsed());
        l.put("timelineCollapsed", layout.timelineCollapsed());
        l.put("sideTab", layout.sideTab());
        out.put("layout", l);
        out.put("theme", theme);
        List<Object> docs = new ArrayList<>();
        for (DocumentState d : documents) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", d.id());
            o.put("path", d.path() == null ? null : d.path().toString());
            o.put("canvasZoom", finite(d.canvasZoom(), 1));
            o.put("canvasPanX", finite(d.canvasPanX(), 0));
            o.put("canvasPanY", finite(d.canvasPanY(), 0));
            o.put("playhead", finite(d.playhead(), 0));
            o.put("selectedLayerIds", d.selectedLayerIds());
            List<Object> keyframes = new ArrayList<>();
            for (KeyframeRef ref : d.selectedKeyframes()) {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("layerId", ref.layerId());
                k.put("propertyKey", ref.propertyKey());
                k.put("keyframeId", ref.keyframeId());
                keyframes.add(k);
            }
            o.put("selectedKeyframes", keyframes);
            o.put("timelineZoom", finite(d.timelineZoom(), 1));
            o.put("timelineScroll", finite(d.timelineScroll(), 0));
            o.put("expandedLayerIds", d.expandedLayerIds());
            o.put("autoKey", d.autoKey());
            o.put("showAllProperties", d.showAllProperties());
            docs.add(o);
        }
        out.put("documents", docs);
        out.put("activeDocumentId", activeDocumentId);
        out.put("recentFiles", recentFiles.stream().map(Path::toString).toList());
        out.put("audioMuted", audioMuted);
        out.put("followSelection", followSelection);
        return out;
    }

    /**
     * Reads a value tree as produced by {@link com.dlsc.animatorfx.model.io.Json#parse}. Missing
     * members take their defaults.
     *
     * @throws IllegalArgumentException if a member has the wrong type
     */
    public static SessionState fromJson(Object json) {
        Map<?, ?> root = object(json, "session");
        SessionState defaults = defaults();

        WindowState window = null;
        if (root.get("window") != null) {
            Map<?, ?> w = object(root.get("window"), "window");
            window = new WindowState(number(w, "x", 0), number(w, "y", 0), number(w, "width", 0),
                    number(w, "height", 0), bool(w, "maximized", false), (int) number(w, "screen", 0));
        }
        Layout layout = Layout.DEFAULT;
        if (root.get("layout") != null) {
            Map<?, ?> l = object(root.get("layout"), "layout");
            layout = new Layout(number(l, "sideDivider", Layout.DEFAULT.sideDivider()),
                    number(l, "timelineDivider", Layout.DEFAULT.timelineDivider()),
                    bool(l, "sidePanelCollapsed", false), bool(l, "timelineCollapsed", false),
                    string(l, "sideTab", Layout.DEFAULT.sideTab()));
        }
        List<DocumentState> documents = new ArrayList<>();
        for (Object item : list(root, "documents")) {
            Map<?, ?> d = object(item, "document");
            String id = string(d, "id", null);
            if (id == null) {
                throw new IllegalArgumentException("A document has no id");
            }
            String path = string(d, "path", null);
            List<KeyframeRef> keyframes = new ArrayList<>();
            for (Object k : list(d, "selectedKeyframes")) {
                Map<?, ?> ref = object(k, "keyframe");
                keyframes.add(new KeyframeRef(string(ref, "layerId", ""), string(ref, "propertyKey", ""),
                        string(ref, "keyframeId", "")));
            }
            documents.add(new DocumentState(id, path == null ? null : Path.of(path), number(d, "canvasZoom", 1),
                    number(d, "canvasPanX", 0), number(d, "canvasPanY", 0), number(d, "playhead", 0),
                    strings(d, "selectedLayerIds"), keyframes, number(d, "timelineZoom", 1),
                    number(d, "timelineScroll", 0), strings(d, "expandedLayerIds"), bool(d, "autoKey", false),
                    bool(d, "showAllProperties", false)));
        }
        List<Path> recent = strings(root, "recentFiles").stream().map(Path::of).toList();
        return new SessionState(window, layout, string(root, "theme", defaults.theme()), documents,
                string(root, "activeDocumentId", null), recent, bool(root, "audioMuted", false),
                bool(root, "followSelection", true));
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static Map<?, ?> object(Object value, String what) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException("Expected an object for " + what);
    }

    private static double number(Map<?, ?> map, String key, double fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("Expected a number for " + key);
    }

    private static boolean bool(Map<?, ?> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("Expected a boolean for " + key);
    }

    private static String string(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("Expected a string for " + key);
    }

    private static List<?> list(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IllegalArgumentException("Expected an array for " + key);
    }

    private static List<String> strings(Map<?, ?> map, String key) {
        List<String> out = new ArrayList<>();
        for (Object item : list(map, key)) {
            if (!(item instanceof String s)) {
                throw new IllegalArgumentException("Expected strings in " + key);
            }
            out.add(s);
        }
        return out;
    }
}
