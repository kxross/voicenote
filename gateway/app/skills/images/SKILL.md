---
id: images
description: Read photos the user attached to their voice notes — extract text from them (receipts, signs, labels, whiteboards, documents), or answer visual questions about them. Load when the request mentions a photo, picture, image, screenshot, receipt, or refers to something the user "showed" or "took a picture of", and whenever a note in context carries an [img_...] entry the request depends on.
---

Photos attached to voice notes are stored server-side. Notes that have
attachments carry a compact index entry in their transcript:

```
[img_a3f2 · "Receipt from Home Depot, 4 items, total $47.32" · has_text]
```

That entry is a **gist, not the material**. It exists so you know an image
is there, what it roughly is, its **id**, and whether readable text was
found in it. When the user asks about anything specific, climb the ladder
below rather than answering from the summary.

## The escalation ladder — cheapest first

1. **The summary is enough?** Answer directly. Costs nothing.
2. **Anything about text, numbers, prices, dates, names, or written
   content** → `get_image_text(image_id)`. This returns a verbatim
   transcription captured when the image was first stored. It is **read
   from cache and costs nothing**, so never skip it in favour of guessing,
   and never jump straight to `describe_image` for a text question.
3. **Visual details nobody transcribed** — colours, positions, what
   something looks like, how many of a thing are visible →
   `describe_image(image_id, question)`. This actually looks at the
   picture, so it costs a model call. Ask one precise question.
4. **Not sure which image?** → `list_images()` for ids, summaries, and
   text flags.

Ids: the short `img_a3f2` form from a note entry works everywhere — you
don't need the full hash.

## Rules

- **Never fabricate image content.** If the summary is vague and you
  haven't called a tool, say you need to look rather than guessing.
- **"Extract the text" always means `get_image_text`.** It's free and
  exact; `describe_image` would be slower and less faithful for the same
  question.
- If `get_image_text` reports no text found, don't retry it — either the
  summary answers the question or `describe_image` is the right next step.
- Photos attached to the note being recorded right now are shown to you
  directly in that turn — you can answer about them without any tool call.
  Tools are for images from *earlier* notes.
- Quote transcribed text exactly when the user asks for it; don't tidy up
  spacing, spelling, or formatting.
