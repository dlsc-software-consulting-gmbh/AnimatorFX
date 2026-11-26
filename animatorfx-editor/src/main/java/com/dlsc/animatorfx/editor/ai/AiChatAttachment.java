package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.Asset;

import java.io.IOException;
import java.util.Objects;

/** An attachment available to an AI chat edit, either from the project or an external file. */
public record AiChatAttachment(String name, AiAttachment.Kind kind, double width, double height, byte[] preview,
                               Asset projectAsset, AiAttachment external) {

    private static final int MAX_PREVIEW_BYTES = 200_000;

    public AiChatAttachment {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(preview, "preview");
        if ((projectAsset == null) == (external == null)) {
            throw new IllegalArgumentException("Exactly one attachment source is required.");
        }
        if (!Double.isFinite(width) || width <= 0 || !Double.isFinite(height) || height <= 0) {
            throw new IllegalArgumentException("Attachment dimensions must be positive and finite.");
        }
        preview = preview.clone();
    }

    @Override
    public byte[] preview() {
        return preview.clone();
    }

    /**
     * Creates a chat attachment backed by an existing project image asset.
     *
     * @throws IOException if the preview cannot be encoded or exceeds 200 KB
     */
    public static AiChatAttachment projectImage(Asset asset, byte[] bytes) throws IOException {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(bytes, "bytes");
        if (asset.kind() != Asset.Kind.IMAGE) {
            throw new IllegalArgumentException("Only image assets can be attached to chat.");
        }
        AiAttachment converted = AiAttachment.image(asset.path(), bytes);
        checkPreviewSize(asset.name(), converted.preview());
        return new AiChatAttachment(asset.name(), converted.kind(), converted.width(), converted.height(),
                converted.preview(), asset, null);
    }

    /**
     * Wraps an externally validated attachment for chat parsing.
     *
     * @throws IOException if the preview exceeds 200 KB
     */
    public static AiChatAttachment external(AiAttachment attachment) throws IOException {
        Objects.requireNonNull(attachment, "attachment");
        checkPreviewSize(attachment.name(), attachment.preview());
        return new AiChatAttachment(attachment.name(), attachment.kind(), attachment.width(), attachment.height(),
                attachment.preview(), null, attachment);
    }

    private static void checkPreviewSize(String name, byte[] preview) throws IOException {
        if (preview.length > MAX_PREVIEW_BYTES) {
            throw new IOException(name + ": preview is larger than 200 KB.");
        }
    }

    /** Returns the request-local ID used by the AI for this attachment index. */
    public static String id(int index) {
        return AiAttachment.id(index);
    }
}
