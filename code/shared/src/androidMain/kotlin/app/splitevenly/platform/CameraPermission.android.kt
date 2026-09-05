package app.splitevenly.platform

/**
 * Android [CameraPermission] — deliberately inert.
 *
 * `FilePicker` reaches the camera through `ActivityResultContracts.TakePicture`, which launches the
 * *system* camera app under its own permissions. `CAMERA` is not in our manifest, and per
 * `platform/AGENTS.md` it must not be added: declaring it makes the OS demand a runtime grant we would
 * then have to ask for, replacing a working zero-dialog flow with a dialog.
 *
 * So there is no state to read and nothing to prompt for. The shared gate sees [CameraPermissionStatus.NotApplicable]
 * and goes straight to the picker.
 */
actual class CameraPermission {

    actual suspend fun status(): CameraPermissionStatus = CameraPermissionStatus.NotApplicable

    actual suspend fun request(): Boolean = true
}
