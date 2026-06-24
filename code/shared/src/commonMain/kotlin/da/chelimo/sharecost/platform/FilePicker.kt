package da.chelimo.sharecost.platform

import da.chelimo.sharecost.core.error.AppResult

/** What the user is allowed to pick (06 §5.1) — narrows the system picker's content types. */
enum class PickKind { Image, Pdf, ImageOrPdf }

/**
 * Where the user picks from. Receipts hide in different places — half in the camera roll, half saved as
 * files — so the Add sheet offers all three: the OS **Photos** picker (multi-select images), the
 * **Files** browser (multi-select images/PDFs), and the **Camera** (snap one fresh photo).
 */
enum class PickSource { Photos, Files, Camera }

/** A picked file's bytes plus the metadata the receipt pipeline (D-22) needs to compress/upload it. */
class PickedFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * E-5 — photo / PDF / file picker (06 §5.1), now **multi-select** and source-aware. **Android**: the
 * system Photo Picker (`PickMultipleVisualMedia`), `OpenMultipleDocuments`, or `TakePicture` (camera via
 * a `FileProvider` temp file). **iOS**: `PHPicker` (multi photos), `UIDocumentPicker` (multi files), or
 * `UIImagePickerController` (camera). Bytes come back in memory; the upload pipeline persists them.
 *
 * [pick] suspends until the user picks or cancels. A cancel resolves to `AppResult.Ok(emptyList())` — a
 * normal outcome, not an error; only a genuine failure (no host activity, unreadable bytes) is an `Err`.
 *
 * Constructor is platform-specific (Android needs a way to reach the foreground activity) — instances
 * come from `platformModule()`.
 */
expect class FilePicker {
    /** Present the [source] picker constrained to [kind]; `Ok(emptyList())` if the user cancels. */
    suspend fun pick(source: PickSource, kind: PickKind = PickKind.ImageOrPdf): AppResult<List<PickedFile>>
}
