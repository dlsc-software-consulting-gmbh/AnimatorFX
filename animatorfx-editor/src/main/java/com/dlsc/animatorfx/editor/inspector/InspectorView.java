package com.dlsc.animatorfx.editor.inspector;

import com.dlsc.animatorfx.editor.audio.SoundPreview;
import com.dlsc.animatorfx.editor.audio.WaveformCache;
import com.dlsc.animatorfx.editor.inspector.sections.AudioSection;
import com.dlsc.animatorfx.editor.inspector.sections.VideoSection;
import com.dlsc.animatorfx.editor.video.VideoImport;
import com.dlsc.animatorfx.editor.inspector.sections.CueSection;
import javafx.beans.WeakInvalidationListener;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.inspector.fields.NumberField;
import com.dlsc.animatorfx.editor.inspector.sections.EasingSection;
import com.dlsc.animatorfx.editor.inspector.sections.EffectsSection;
import com.dlsc.animatorfx.editor.inspector.sections.FillSection;
import com.dlsc.animatorfx.editor.inspector.sections.GeometrySection;
import com.dlsc.animatorfx.editor.inspector.sections.ImageSection;
import com.dlsc.animatorfx.editor.inspector.sections.LayerSection;
import com.dlsc.animatorfx.editor.inspector.sections.MaskSection;
import com.dlsc.animatorfx.editor.inspector.sections.MotionPathSection;
import com.dlsc.animatorfx.editor.inspector.sections.SlotSection;
import com.dlsc.animatorfx.editor.inspector.sections.StrokeSection;
import com.dlsc.animatorfx.editor.inspector.sections.TextSection;
import com.dlsc.animatorfx.editor.inspector.sections.TransformSection;
import com.dlsc.animatorfx.editor.inspector.sections.TrimSection;
import java.util.List;
import java.util.Optional;
import javafx.beans.InvalidationListener;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The Inspector tab: the properties of the selected layers and keyframes, or of the selected
 * sound cues, as an ordered list of {@link InspectorSection}s. A section shows while it applies
 * to the selection; without a selected layer or cue there is only a hint.
 *
 * <p>The sections are built once. A change of the document or the selection decides which of
 * them show and updates their values; a move of the playhead only updates the values.
 *
 * <p>To add a section, insert it into {@link #getSections()} at the position where it should
 * appear.
 */
public final class InspectorView extends StackPane {

    private final ObjectProperty<EditorDocument> document = new SimpleObjectProperty<>(this, "document");
    private final ObservableList<InspectorSection> sections = FXCollections.observableArrayList();
    private final VBox box = new VBox();
    private final ScrollPane scroll = new ScrollPane(box);
    private final VBox empty = new VBox();
    private InspectorContext context;
    private final InvalidationListener refresh = obs -> refresh();
    // An arrow key or scrub that is still held when the playhead or the selection changes must
    // not go on in the same undo step: it now edits another time or other layers.
    private final InvalidationListener tick = obs -> {
        NumberField.endOpenAdjustment();
        updateValues();
    };
    private final InvalidationListener infoArrived = obs -> updateValues();
    private final InvalidationListener selection = obs -> {
        NumberField.endOpenAdjustment();
        SoundPreview.shared().stop();
        refresh();
    };

    public InspectorView() {
        getStyleClass().add("inspector");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("inspector-scroll");
        box.getStyleClass().add("inspector-sections");
        Label title = new Label("No selection");
        title.getStyleClass().add("empty-title");
        Label hint = new Label("Select a layer on the canvas or in the timeline to edit its properties.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        empty.getStyleClass().add("inspector-empty");
        empty.getChildren().addAll(title, hint);
        getChildren().addAll(scroll, empty);

        sections.addListener((InvalidationListener) obs -> {
            box.getChildren().setAll(sections.stream().map(InspectorSection::node).toList());
            refresh();
        });
        sections.addAll(new EasingSection(), new LayerSection(), new TransformSection(), new GeometrySection(),
                new FillSection(), new StrokeSection(), new TrimSection(), new TextSection(), new ImageSection(),
                new VideoSection(), new AudioSection(), new CueSection(), new SlotSection(), new EffectsSection(), new MaskSection(), new MotionPathSection());

        // the length of a sound may become known after the section shows
        WaveformCache.shared().revisionProperty().addListener(new WeakInvalidationListener(infoArrived));
        VideoImport.revisionProperty().addListener(new WeakInvalidationListener(infoArrived));
        document.addListener((obs, oldDoc, newDoc) -> {
            if (oldDoc != null) {
                oldDoc.animationProperty().removeListener(refresh);
                oldDoc.getSelection().layerIds().removeListener(selection);
                oldDoc.getSelection().cueIds().removeListener(selection);
                oldDoc.getSelection().keyframes().removeListener(refresh);
                oldDoc.playheadProperty().removeListener(tick);
            }
            SoundPreview.shared().stop();
            context = newDoc == null ? null : new InspectorContext(newDoc);
            if (newDoc != null) {
                newDoc.animationProperty().addListener(refresh);
                newDoc.getSelection().layerIds().addListener(selection);
                newDoc.getSelection().cueIds().addListener(selection);
                newDoc.getSelection().keyframes().addListener(refresh);
                newDoc.playheadProperty().addListener(tick);
            }
            refresh();
        });
        refresh();
    }

    /**
     * The document whose selection the inspector shows and edits.
     *
     * @return the document property
     */
    public final ObjectProperty<EditorDocument> documentProperty() {
        return document;
    }

    public final EditorDocument getDocument() {
        return document.get();
    }

    public final void setDocument(EditorDocument value) {
        document.set(value);
    }

    /** The sections in display order; the list can be changed. */
    public ObservableList<InspectorSection> getSections() {
        return sections;
    }

    /** The first section of that type. */
    public <S extends InspectorSection> Optional<S> section(Class<S> type) {
        return sections.stream().filter(type::isInstance).map(type::cast).findFirst();
    }

    /** The sections that are showing for the current selection. */
    public List<InspectorSection> getVisibleSections() {
        return sections.stream().filter(s -> s.node().isVisible()).toList();
    }

    /** What the sections work on; {@code null} without a document. */
    public InspectorContext getContext() {
        return context;
    }

    /** Decides which sections show for the selection and updates them. */
    private void refresh() {
        boolean selection = context != null && (!context.layers().isEmpty() || !context.cues().isEmpty());
        empty.setVisible(!selection);
        scroll.setVisible(selection);
        // a locked layer is unlocked before it is edited; the Layer section keeps its lock toggle usable
        boolean locked = selection && context.layers().stream().anyMatch(layer -> layer.base().locked());
        for (InspectorSection section : sections) {
            boolean applies = selection && section.appliesTo(context);
            section.node().setVisible(applies);
            section.node().setManaged(applies);
            section.node().setDisable(locked && !(section instanceof LayerSection));
            if (applies) {
                section.update(context);
            }
        }
    }

    /** The playhead moved: the same sections, new values. */
    private void updateValues() {
        if (context == null || !scroll.isVisible()) {
            return;
        }
        for (InspectorSection section : sections) {
            if (section.node().isVisible()) {
                section.update(context);
            }
        }
    }
}
