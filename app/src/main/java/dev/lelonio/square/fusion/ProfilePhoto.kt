package dev.lelonio.square.fusion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Square Fusion: a profile picture chosen on the phone, shown in place of the
 * YouTube account's. Kept in the app's own files and never sent anywhere: the
 * YouTube profile is not changed.
 */
object ProfilePhoto {

    private var dir: File? = null
    private val _custom = MutableStateFlow<String?>(null)

    /** The chosen picture's address, or null for the account's own. */
    val custom: StateFlow<String?> = _custom.asStateFlow()

    fun init(context: Context) {
        if (dir != null) return
        dir = context.applicationContext.filesDir
        _custom.value = current()?.let { android.net.Uri.fromFile(it).toString() }
    }

    /** Takes [picture] (from the system's photo picker), square and at most [SIDE] pixels. */
    suspend fun set(context: Context, picture: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        val folder = dir ?: return@withContext false
        runCatching {
            val source = context.contentResolver.openInputStream(picture)?.use { BitmapFactory.decodeStream(it) }
                ?: error("cannot read $picture")
            val side = minOf(source.width, source.height)
            val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
            val scaled = if (side > SIDE) Bitmap.createScaledBitmap(square, SIDE, SIDE, true) else square
            // A new name each time, so the image loader does not show the last one.
            val file = File(folder, "$PREFIX${System.currentTimeMillis()}.jpg")
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            folder.listFiles()?.filter { it.name.startsWith(PREFIX) && it != file }?.forEach { it.delete() }
            _custom.value = android.net.Uri.fromFile(file).toString()
        }.onFailure { FusionLog.w("profile picture not kept: ${it.message}") }.isSuccess
    }

    /** Back to the account's own picture. */
    fun clear() {
        dir?.listFiles()?.filter { it.name.startsWith(PREFIX) }?.forEach { it.delete() }
        _custom.value = null
    }

    private fun current(): File? =
        dir?.listFiles()?.filter { it.name.startsWith(PREFIX) && it.isFile }?.maxByOrNull { it.name }

    private const val PREFIX = "square_fusion_profile_"
    private const val SIDE = 512
}
