# Changelog

## Unreleased
- Vendor the required libmobi C sources and licensing texts so native MOBI conversion builds without parent-directory dependencies; generate the self-authored MOBI test fixture during Android test builds.
- Add Spanish build/privacy/limitations and contribution guidance, AGPL-3.0-or-later license, safe issue/security templates, and Android CI/debug-signed tagged releases with checksums.
- Deploy writing tools in a persistent nonmodal panel controlled by explicit writing mode, independent of auto-hidden reader chrome. Tool/color/width and page-scoped undo/redo/clear actions retain the panel; its close control stays pinned while settings scroll.
- Use pressure-aware AndroidX Ink pen geometry with lossless finite pressure/elapsed-time v4 round-trips and exact live/replay transforms. Keep the fixed-tip marker opaque and pressure-independent, with a wider 12 pt default versus the 4 pt pen.
- Resolve bounded visible-page text layouts natively first, then from a neutral orientation-correct rendered page using the bundled on-device Latin OCR model. Cancel across book/reflow changes, cache independent of zoom, and publish only to the matching book/layout generation.
- Highlight only hit OCR/native words joined into same-line text bands; blank/unrecognized pages commit no text-highlight stroke. Preserve legacy rectangular highlights and freehand highlight behavior; expose resolved text/status to selection and quote surfaces.
- Keep finger scrolling/page controls and ordinary hardware keys usable while stylus pointer gestures/edge navigation and stylus key 308/309 cannot turn pages during writing. Correct AndroidView pointer coordinates through reader zoom.
- ReaderInkTest’s two instrumentation cases passed on the authorized Xiaomi Pad 7 (2026-10-03): isolated image-only PDF and CBZ Latin OCR, word selection/highlights at zoom, copy/quote, blank-page no-fallback, canceled stale-page/book work, persistent panel, finger scrolling, and synthetic stylus-key/gesture routing; pressure geometry measured pen 6.00003→9.00006 px and marker 12→12 px. Proof screenshots were captured outside the repository. Stylus navigation was injected in tests rather than exercised with a physical pen; disconnected-network behavior was not tested.
- Make library cards less cluttered with favorite and overflow actions, add adaptive grid and persisted compact-list modes, and provide contextual author/series/format/folder search.
- Complete semantic light, dark, and OLED theme roles while keeping dynamic color opt-in and disabled by default.
- Keep visible PDF/CBZ controls outside fixed-page content and preserve EPUB pagination when reader chrome is shown or hidden.
- Refresh the Compose interface with Axiom's graphite/slate identity, touch-sized library actions, restrained settings and statistics, factual reading milestones, and paper/graphite quote cards.
- Rename the Gradle project and Android app to Axiom, and replace the launcher artwork with the supplied logo.
- Enable full-text PDF search on supported Android versions, with up to 50 contextual results per query.
- Add non-destructive PDF page reorder, rotation, deletion, extraction, merge, OCR/searchable copies, and PDF-to-image/text exports.
- Keep imported PDF streams live through file-backed merges and prioritize the next PDF page within the bitmap cache budget.
- Add a visual signature on a selected PDF page, saved as a new copy with explicit library/Drive-sync consent; no certificate-backed signing is implied.
- Add ML Kit document scanning and image-to-PDF creation; register generated documents in the library for existing Google Drive upload.
- Add a consent-gated OpenRouter Free PDF assistant and visible API-key settings using Android Keystore encryption; exclude the encrypted key from backup.
- Complete the Google Drive backup contract with versioned library snapshots, safe portable preferences, checksum-verified book/cover attachments, and automatic restore of remote-only books.
- Keep each presentation laser stroke visible for five seconds; consecutive strokes expire independently.
- Export per-book quotes, notes, and bookmarks to Markdown; persist and apply bold typography in EPUB/TXT, remove unsupported reader controls, and reject catalog requests to non-public destinations and unsafe redirects.
- Keep active-book drilldowns current with count and metadata updates, deriving groups only when books change and off the main thread.
