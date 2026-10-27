package dev.lelonio.square.fusion

/**
 * The log every Square Fusion addition writes to.
 *
 * A wrapper rather than android.util.Log directly so the source code can run
 * in plain JVM unit tests, where the Android log is a stub that throws. Remote
 * URLs are never written whole: an addon may carry a private token in its path,
 * see [dev.lelonio.square.fusion.sources.addon.AddonClient.redact].
 */
object FusionLog {
    const val TAG = "SquareFusion"

    fun d(message: String) = write { android.util.Log.d(TAG, message) }
    fun i(message: String) = write { android.util.Log.i(TAG, message) }
    fun w(message: String) = write { android.util.Log.w(TAG, message) }

    private inline fun write(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // JVM tests: no Android log to write to.
        }
    }
}
