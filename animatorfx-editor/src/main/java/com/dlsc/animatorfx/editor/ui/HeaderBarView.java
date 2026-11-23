package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.canvas.CanvasView;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;

/**
 * The window header: application identity, document tabs, canvas zoom and the theme switch, alongside the
 * system-provided window buttons.
 */
public final class HeaderBarView extends HeaderBar {

    public HeaderBarView(DocumentTabs tabs, Theme theme, Image icon) {
        this(tabs, theme, icon, null);
    }

    public HeaderBarView(DocumentTabs tabs, Theme theme, Image icon, CanvasView canvas) {
        getStyleClass().add("header-bar");

        ImageView image = new ImageView(icon);
        image.setFitWidth(20);
        image.setFitHeight(20);
        image.setPreserveRatio(true);
        Label brand = new Label("AnimatorFX", image);
        brand.getStyleClass().add("brand");
        brand.setGraphicTextGap(8);
        brand.setMinWidth(USE_PREF_SIZE);
        setDragType(brand, HeaderDragType.DRAGGABLE_SUBTREE);
        setLeft(brand);

        ScrollPane documents = new ScrollPane(tabs);
        documents.getStyleClass().add("header-tabs");
        documents.setFitToHeight(true);
        documents.setFitToWidth(true);
        documents.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        documents.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        documents.setPannable(true);
        documents.setFocusTraversable(false);
        documents.setMinWidth(0);
        documents.setMaxHeight(32);
        setDragType(tabs, HeaderDragType.DRAGGABLE);
        setAlignment(documents, Pos.CENTER_LEFT);
        setMargin(documents, new Insets(0, 16, 0, 16));
        setCenter(documents);

        FontIcon themeIcon = new FontIcon();
        themeIcon.iconCodeProperty().bind(Bindings.when(theme.darkProperty())
                .then((Ikon) Material2MZ.NIGHTS_STAY).otherwise(Material2MZ.WB_SUNNY));
        Button themeToggle = new Button(null, themeIcon);
        themeToggle.setId("theme-toggle");
        themeToggle.getStyleClass().addAll("icon-button", "pill");
        themeToggle.setFocusTraversable(false);
        themeToggle.setTooltip(new Tooltip("Switch between dark and light"));
        themeToggle.setOnAction(e -> theme.toggle());
        if (canvas == null) {
            setRight(themeToggle);
        } else {
            Label zoom = new Label();
            zoom.setId("zoom");
            zoom.getStyleClass().addAll("pill", "mono");
            zoom.setMinWidth(USE_PREF_SIZE);
            zoom.setTooltip(new Tooltip("Canvas zoom"));
            canvas.documentProperty().addListener((obs, oldDoc, newDoc) -> bindZoom(zoom, newDoc));
            bindZoom(zoom, canvas.getDocument());

            Button zoomOut = zoomButton("zoom-out", Material2MZ.ZOOM_OUT, "Zoom out", canvas, canvas::zoomOut);
            Button zoomIn = zoomButton("zoom-in", Material2MZ.ZOOM_IN, "Zoom in", canvas, canvas::zoomIn);
            Button actualSize = zoomButton("zoom-actual-size", Material2AL.LOOKS_ONE, "Actual size",
                    canvas, canvas::zoomToActualSize);
            Button zoomFit = zoomButton("zoom-fit", Material2MZ.ZOOM_OUT_MAP, "Zoom to fit",
                    canvas, canvas::zoomToFit);
            HBox right = new HBox(zoomOut, zoom, zoomIn, actualSize, zoomFit, themeToggle);
            right.getStyleClass().add("toolbar-group");
            setRight(right);
        }
    }

    private static void bindZoom(Label zoom, EditorDocument doc) {
        zoom.textProperty().unbind();
        if (doc == null) {
            zoom.setText("");
        } else {
            zoom.textProperty().bind(Bindings.createStringBinding(
                    () -> Formats.zoom(doc.getViewState().getCanvasZoom()), doc.getViewState().canvasZoomProperty()));
        }
    }

    private static Button zoomButton(String id, Ikon icon, String tooltip, CanvasView canvas, Runnable action) {
        Button button = new Button(null, new FontIcon(icon));
        button.setId(id);
        button.getStyleClass().add("icon-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        button.disableProperty().bind(canvas.documentProperty().isNull());
        button.setOnAction(e -> action.run());
        return button;
    }
}
