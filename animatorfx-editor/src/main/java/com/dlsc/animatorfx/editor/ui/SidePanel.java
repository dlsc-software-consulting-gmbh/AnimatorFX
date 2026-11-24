package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.inspector.AssetsView;
import com.dlsc.animatorfx.editor.inspector.DocumentView;
import com.dlsc.animatorfx.editor.inspector.InspectorView;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

/**
 * The tabbed panel at the right: Inspector ({@link InspectorView}), Assets ({@link AssetsView})
 * and Document ({@link DocumentView}), all following the document of the panel.
 */
public final class SidePanel extends StackPane {

    public static final String INSPECTOR = "inspector";
    public static final String ASSETS = "assets";
    public static final String DOCUMENT = "document";
    static final String AI_CONSOLE = "ai-console";

    private final ObjectProperty<EditorDocument> document = new SimpleObjectProperty<>(this, "document");
    private final TabPane tabs = new TabPane();
    private final InspectorView inspector = new InspectorView();
    private final AssetsView assets = new AssetsView();
    private final DocumentView documentView = new DocumentView();
    private Tab aiConsoleTab;
    private boolean selectAiConsoleWhenAvailable;

    public SidePanel() {
        getStyleClass().add("side-panel");
        setMinWidth(0);
        tabs.setId("side-panel-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(tab(INSPECTOR, "Inspector", inspector), tab(ASSETS, "Assets", assets),
                tab(DOCUMENT, "Document", documentView));
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) ->
                selectAiConsoleWhenAvailable = false);
        getChildren().add(tabs);
        // Delete and Backspace that a control of the panel did not use must not reach the canvas,
        // where they would delete the selected layers
        addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                event.consume();
            }
        });
        inspector.documentProperty().bind(document);
        assets.documentProperty().bind(document);
        documentView.documentProperty().bind(document);
    }

    /**
     * The document that the panel describes.
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

    public InspectorView getInspector() {
        return inspector;
    }

    public AssetsView getAssets() {
        return assets;
    }

    public DocumentView getDocumentView() {
        return documentView;
    }

    /**
     * The id of the selected tab: {@link #INSPECTOR}, {@link #ASSETS} or {@link #DOCUMENT}.
     */
    public String getActiveTab() {
        return tabs.getSelectionModel().getSelectedItem().getId();
    }

    /**
     * Selects the tab with that id; an unknown id leaves the selection as it is.
     */
    public void setActiveTab(String tabId) {
        Tab match = tabs.getTabs().stream().filter(t -> t.getId().equals(tabId)).findFirst().orElse(null);
        selectAiConsoleWhenAvailable = match == null && AI_CONSOLE.equals(tabId);
        if (match != null) tabs.getSelectionModel().select(match);
    }

    /**
     * Replaces the content of one of the tabs.
     *
     * @throws IllegalArgumentException if there is no tab with that id
     */
    public void setTabContent(String tabId, Node content) {
        Tab tab = tabs.getTabs().stream().filter(t -> t.getId().equals(tabId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such tab: " + tabId));
        tab.setContent(content);
    }

    /**
     * The tab pane, for listening to tab changes.
     */
    public TabPane getTabPane() {
        return tabs;
    }

    void setAiConsoleAvailable(Node content, boolean available) {
        if (available) {
            if (aiConsoleTab == null) aiConsoleTab = tab(AI_CONSOLE, "AI Console", content);
            if (!tabs.getTabs().contains(aiConsoleTab)) tabs.getTabs().add(aiConsoleTab);
            if (selectAiConsoleWhenAvailable) {
                selectAiConsoleWhenAvailable = false;
                tabs.getSelectionModel().select(aiConsoleTab);
            }
        } else if (aiConsoleTab != null) {
            tabs.getTabs().remove(aiConsoleTab);
        }
    }

    private static Tab tab(String id, String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setId(id);
        return tab;
    }
}
