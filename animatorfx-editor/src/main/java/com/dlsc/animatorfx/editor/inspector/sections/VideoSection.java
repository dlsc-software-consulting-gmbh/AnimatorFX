package com.dlsc.animatorfx.editor.inspector.sections;

import com.dlsc.animatorfx.editor.doc.PropertyUnits;
import com.dlsc.animatorfx.editor.inspector.InspectorContext;
import com.dlsc.animatorfx.editor.inspector.PropertyRows;
import com.dlsc.animatorfx.editor.inspector.SectionBase;
import com.dlsc.animatorfx.editor.inspector.fields.NumberField;
import com.dlsc.animatorfx.editor.video.VideoImport;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.VideoLayer;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/** Source, rectangular size and soundtrack controls for selected video layers. */
public final class VideoSection extends SectionBase {
    private final ComboBox<Asset> asset = SoundFields.assetCombo("inspector-video-asset");
    private final Label info = SoundFields.readout("inspector-video-info");
    private final NumberField width = new NumberField(PropertyUnits.NONE);
    private final NumberField height = new NumberField(PropertyUnits.NONE);
    private final NumberField offset = new NumberField(PropertyUnits.SECONDS);
    private final CheckBox loop = new CheckBox("Loop");
    private final CheckBox mute = new CheckBox("Mute");
    private boolean updating;

    public VideoSection() {
        super("Video");
        row("Asset", asset);
        row("Clip", info);
        asset.valueProperty().addListener((observable, before, value) -> {
            if (!updating && value != null) {
                context().edit("Change Video", VideoLayer.class, layer -> layer.withAssetId(value.id()), null);
            }
        });
        width.setId("inspector-video-width");
        height.setId("inspector-video-height");
        offset.setId("inspector-video-offset");
        for (NumberField field : new NumberField[]{width, height, offset}) {
            field.setRange(field == offset ? 0 : 1, Double.MAX_VALUE);
            field.setDecimals(field == offset ? 3 : 1);
            field.setMaxWidth(Double.MAX_VALUE);
            field.setOnAdjustEnd(() -> context().endGesture());
        }
        width.setOnChange((value, adjusting) -> context().edit("Change Width", VideoLayer.class,
                layer -> layer.withWidth(value), adjusting ? width : null));
        height.setOnChange((value, adjusting) -> context().edit("Change Height", VideoLayer.class,
                layer -> layer.withHeight(value), adjusting ? height : null));
        offset.setOnChange((value, adjusting) -> context().edit("Change Clip Offset", VideoLayer.class,
                layer -> layer.withClipOffset(value), adjusting ? offset : null));
        width.installScrubbing(row("Width", width).getLabel());
        height.installScrubbing(row("Height", height).getLabel());
        offset.installScrubbing(row("Offset", offset).getLabel());
        PropertyRows.NumberRow volume = number("volume", "Volume");
        volume.field().setRange(0, 1);
        volume.field().setDecimals(0);
        loop.setId("inspector-video-loop");
        mute.setId("inspector-video-mute");
        loop.getStyleClass().add("section-check");
        mute.getStyleClass().add("section-check");
        loop.setOnAction(event -> {
            if (!updating) context().edit("Loop Video", VideoLayer.class,
                    layer -> layer.withLoop(loop.isSelected()), null);
        });
        mute.setOnAction(event -> {
            if (!updating) context().edit("Mute Video", VideoLayer.class,
                    layer -> layer.withMuted(mute.isSelected()), null);
        });
        HBox switches = new HBox(loop, mute);
        switches.getStyleClass().add("check-row");
        row("", switches);
    }

    @Override
    public boolean appliesTo(InspectorContext context) {
        return context.allLayers(VideoLayer.class);
    }

    @Override
    protected void updateFields(InspectorContext context) {
        updating = true;
        try {
            var videos = context.animation().assets().stream().filter(value -> value.kind() == Asset.Kind.VIDEO).toList();
            if (!asset.getItems().equals(videos)) asset.getItems().setAll(videos);
            String id = context.common(VideoLayer.class, VideoLayer::assetId);
            Asset source = videos.stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
            asset.setValue(source);
            info.setText(source == null ? "" : VideoImport.info(context.document(), source)
                    .map(value -> value.description()).orElse(
                            VideoImport.metadata(context.document(), source).isCompletedExceptionally()
                                    ? "Video metadata unavailable" : "Loading video metadata..."));
            loop.setSelected(context.layers().stream().allMatch(layer -> ((VideoLayer) layer).loop()));
            mute.setSelected(context.layers().stream().allMatch(layer -> ((VideoLayer) layer).muted()));
            width.setValue(context.common(VideoLayer.class, VideoLayer::width));
            height.setValue(context.common(VideoLayer.class, VideoLayer::height));
            offset.setValue(context.common(VideoLayer.class, VideoLayer::clipOffset));
        } finally {
            updating = false;
        }
    }
}
