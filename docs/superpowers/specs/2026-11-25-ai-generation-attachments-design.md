# AI Generation Attachments — Design

## Goal

Let the user attach images and icons to an initial AI generation. The AI may place an attachment in the
animation or use it only as a design hint (palette, style, layout). The AI decides from the prompt.

## Scope

- Initial generation only (the "Generate Animation" dialog). Chat edits are out of scope.
- Supported files: PNG, JPEG, GIF (raster) and SVG (vector). WebP is not supported, because neither
  JavaFX nor the JDK can decode it.
- Limits: at most 5 attachments, each at most 5 MB.

## User Interface

`AiActions` gets an "Attachments" strip below the prompt:

- An "Add files…" button opens a file chooser filtered to `*.png, *.jpg, *.jpeg, *.gif, *.svg`.
- Files can also be dropped onto the strip.
- Each attachment is a chip with a thumbnail, the file name and a remove button.
- Files that cannot be read, have an unsupported type or exceed a limit are rejected with an inline
  message. The dialog stays open.

Notifications and the AI Generations popover show the number of attachments of a job.

## Data Flow

1. The dialog produces an immutable `AiAttachment(id, name, type, bytes, width, height)` list. The ids
   are `asset1` … `asset5`, in the order the files were added. SVG width and height come from
   `SvgImporter`.
2. `AiGenerationService` / `Generator.start` receive the attachments together with the prompt.
3. `AiProviderClient.generate` sends each attachment as a vision image through the existing image
   path used by `review()`. Each attachment gets a PNG preview of at most 768 px on the longest
   side; if it is larger than 200 KB it is re-encoded at 512, 384 and finally 256 px. This keeps five
   previews within the 2 MB request limit. SVGs are rasterized by rendering the imported layers.
4. The instructions get an attachment manifest, for example
   `asset1: logo.svg (vector, 120×40)`, which says that attachments may be placed by id or used only
   as design hints, and that unreferenced attachments are not embedded. The text "Do not include
   image ... or external assets" is relaxed to allow exactly these ids.
5. The response is parsed by `AiAnimation` (see Schema) and post-processed (see Expansion).

## Schema

`layers[].content` gets two new kinds:

- `image { kind: "image", asset: string, size }` — a raster attachment drawn into `size`.
- `vector { kind: "vector", asset: string, size }` — an SVG attachment fitted into `size`.

The id is a string in the schema, so the schema stays the same for every request. The parser checks that the id exists and matches the kind (raster
vs. vector). Otherwise it fails with a path-specific error, which the existing repair loop sends
back to the model.

The JSON schema grows by two small branches; the Anthropic grammar-size limit must be rechecked
with the existing test.

## Expansion

After parsing, only referenced attachments are embedded:

- Raster: an `Asset(id, IMAGE, "assets/<id>.<ext>", name)` and an `ImageLayer` scaled to `size`. The
  original bytes go into the `AnimationFile` asset map.
- Vector: the SVG is imported with `SvgImporter`; its layers are wrapped in a `GroupLayer` that
  is scaled uniformly and centred in `size`. The imported layers, which the importer creates with a
  fixed 3-second range, are retimed to the AI layer's in and out points. Assets created by the import, such as embedded
  bitmaps, are merged into the document.

The layer keeps everything the AI set for it (name, transform, opacity, keyframes, parent), so
the attachment animates like any other layer.

## Error Handling

- Unknown or wrongly typed id → repair loop, as for any other schema violation.
- An SVG that fails to import when attached → rejected in the dialog.
- Provider-side image errors surface through `AiErrors` like other request failures.

## Testing

- Dialog: adding, removing and rejecting files (type, size, count).
- Provider client: the generation request contains one image block per attachment and the manifest.
- Schema/parser: valid image and vector references parse; unknown or mismatched ids fail with the
  right path.
- Expansion: a raster reference produces an `Asset` + `ImageLayer` with bytes; a vector reference
  produces an editable `GroupLayer`; unreferenced attachments are not embedded.
- The Anthropic grammar-limit test still passes.
