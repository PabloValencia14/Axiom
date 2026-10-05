# Changelog

## Unreleased

- Batch automatic library scans with a 30-second resume throttle while explicit refresh remains immediate; preserve curated metadata, annotations, trashed records and their covers. Move latest-wins filtering/sorting off the main thread and debounce only text matching.
- Share preview and full-page bitmaps under a 32–128 MiB cache budget with a moving prefetch window; make movement lock prevent touch pan/zoom while keeping page navigation available.
- Add labeled 48 dp theme choices, working General settings navigation, fixed-layout-only CBZ controls, and truthful empty-page/Stop behavior for TTS.
- Translate every page with native text (no former 200-page cap), omit pages without text, disclose sending text to Google Translate, fail on translation errors, support cancellation and write collision-safe unique outputs.
- Add native-text translation for PDF, DOCX, EPUB, FB2 and TXT, keeping originals unchanged. PDF copies preserve page graphics/layout and reject text that cannot be safely fitted; DOCX structure/resources remain intact with possible repagination, while EPUB and Kindle-converted EPUB may reflow. Document the supported-text and fidelity limits.
- Preserve PDF text rendering modes 1 and 2 when translating native text, retaining source fill/stroke colors and inherited stroke state; expand source and fitted ink bounds for line width and transforms, and keep runs separate when dash state changes. Clipping modes, invisible text and unsupported pattern fills remain rejected.
- Verification: all 14 `PdfDocumentTranslatorTest` cases passed; offline debug and Android-test APK builds succeeded. The isolated `nativeTranslationCopyPreservesGraphicsAndReopensInActualEngine` instrumentation test passed on authorized device 97c290d2 using a generated PDF and injected translator (no provider calls or library/preferences/sync changes). Source and translated renderer evidence: `proof_screenshots/pdf_stroke_translation_source.png` and `proof_screenshots/pdf_stroke_translation_smoke.png`.
- Adapt library layouts and empty states; show the runtime app version and accurate format/dependency/license information without implying that format hints add reader engines.
- Reject app-private paths and records at import/Drive attachment boundaries; bind OAuth state to the selected account, cancel stale work and reject late results. Legacy Google tokens without an account binding require reauthorization. Apply bounded stream handling and guarded requests to LibGen search and intermediate pages.
- Enforce these limits: documents 256 MiB, covers 8 MiB, snapshots 16 MiB, catalog pages 4 MiB; EPUB members 2 MiB each, 16 MiB cumulative decompressed data, 10,000 ZIP/manifest entries and 2,000 spine references.
- Verification: 96 unit tests across 26 classes passed with zero failures, errors or skips; five permanent Xiaomi Pad 7 fixture cases passed. Read-only browsing of the real library was performed. A separate final MainActivity Settings UI smoke passed (General, disclosure, Reading, Other and About; one instrumented test) and confirmed version 1.0.1 and reader-versus-indexed format guidance. No universal smoothness/benchmark or live remote-service validation is claimed.

## 1.0.1 — 2026-10-03
- Render writing tools as a compact floating capsule bar overlay directly over the document viewport, removing the 364 dp side reservation and preserving full viewport width across writing state transitions.
- Streamline the toolbar into an icon-only Concepts-inspired instrument strip (5 tools, 3 stroke line samples, 6 color discs, nonmodal undo/redo/clear, OCR status icon, and explicit close button) with zero persistent visible text in the bar and full accessibility semantics intact. Contextual width and color controls are hidden in eraser mode.
- Adapt layout responsively between a sleek single-row horizontal capsule on tablet (landscape and portrait) and a compact <=144 dp two-row layout on narrow viewports with the close control pinned and tool rows horizontally scrollable without clipping.
- Prevent panel surface taps from triggering ink strokes or page turns beneath, while drawing outside the bar continues uninterrupted without dismissing the panel.
- Validate via ReaderInkTest on authorized Xiaomi Pad 7 (97c290d2): all 3 test cases passed (focused floating toolbar smoke/no-text regression, durable ink persistence/replay/rotation, and real OCR/highlight routing) with fresh light, dark, landscape, portrait, and narrow proof screenshots captured.
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
