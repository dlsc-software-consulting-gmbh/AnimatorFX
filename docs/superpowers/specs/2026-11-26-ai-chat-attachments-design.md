# AI Chat Attachments — Design

## Goal

Let users explicitly give the AI Console visual assets to inspect and use in animation edits. The
AI may reuse an image already in the open project or place an external image/SVG as a new layer.

## Scope and user experience

- Add a removable attachment tray to the AI Chat composer.
- Let users select image assets from the active project, add external files with a file picker, or
  drop supported files onto the composer.
- Show attachment previews/names and allow removal before sending. Never send the full project
  asset library automatically.
- Populate project choices from image metadata and byte availability, decoding only selected images.
  Refresh choices and selected previews on same-document edits and undo/redo. Revalidate selected
  project images against the exact submission snapshot; missing or unreadable images block sending
  with an inline error.
- Support project `IMAGE` assets and external PNG, JPEG, GIF, and SVG files. Audio, video, and font
  assets are out of scope.
- An attachment is available only to the message that includes it. Later turns require reattaching
  it; the transcript may retain its name, but attachment bytes are not retained or resent.

## Data flow

1. The composer captures selected project images and external files as a per-message attachment
   list. Existing project images are previewed locally; external files use the validation and
   preview-generation path from AI Generation attachments.
2. The edit request contains the usual project structure and text plus PNG preview images and a
   manifest assigning request-local IDs (`asset1` … `asset5`). No local paths or unselected asset
   data are sent. Attachment names appear only as structured user-message metadata, never as system
   instructions; the model is explicitly told that names are not instructions.
3. The edit parser accepts only IDs in the current request manifest and verifies raster/vector
   type matches. It maps selected project images back to their existing project asset IDs. External
   raster files become embedded assets only if referenced by a generated layer; SVG files are
   expanded into editable vector layers as in AI Generation.
   Project SVGs remain existing project `IMAGE` assets, even when advertised as vector attachments.
   Repeated external raster references across all add operations share one imported asset and byte
   payload while producing distinct layers.
4. The parsed result carries any new external asset metadata and bytes. `EditorDocument.execute`
   applies those together with the animation edit as one undoable operation. Unreferenced
   attachments are not added to the project.
5. After the request completes or is cancelled, attachment bytes are discarded from chat state.
   The conversation history remains text-only and tells the AI that images from earlier turns are
   unavailable unless reattached.

## Limits and failure handling

- At most five attachments per message.
- External files retain the existing 5 MB per-file input limit; each provider preview is bounded to
  200 KB, as in the generation attachment flow.
- Invalid, unsupported, or oversized files are rejected with an inline composer error.
- Unknown attachment IDs and raster/vector mismatches are rejected by the existing edit validation
  and repair flow. A failed request does not mutate the document.

## Testing

- Chat UI: selecting project images, dropping/choosing external files, removing items, and clearing
  the composer after send.
- Provider client: only current-message previews are sent as image content with the matching
  manifest.
- Edit parsing: selected project asset reuse, external raster insertion with bytes, SVG expansion,
  unknown IDs, type mismatches, and unused attachments.
- Document integration: new assets and layers form one undo/redo step; project assets and bytes
  remain intact.
- Conversation behavior: prior message attachment bytes are not retained or resent; users must
  reattach them for a later turn.
