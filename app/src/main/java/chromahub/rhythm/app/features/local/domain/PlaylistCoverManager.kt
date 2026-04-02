package chromahub.rhythm.app.features.local.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PlaylistCoverManager(private val context: Context) {

    companion object {
        private const val COVERS_DIR = "playlist_covers"
    }

    suspend fun saveCustomCover(playlistId: String, sourceUri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, COVERS_DIR).also { it.mkdirs() }
                val dest = File(dir, "playlist_$playlistId.jpg")
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Uri.fromFile(dest)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    fun deleteCustomCover(playlistId: String) {
        val file = File(context.filesDir, "$COVERS_DIR/playlist_$playlistId.jpg")
        if (file.exists()) {
            file.delete()
        }
    }

    suspend fun generateMosaicCover(songs: List<Song>): Bitmap? =
        withContext(Dispatchers.IO) {
            val size = 512
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val artUris = songs
                .mapNotNull { it.artworkUri } // It's artworkUri in Song, not albumArtUri
                .distinct()
                .take(4)

            if (artUris.isEmpty()) return@withContext null

            artUris.forEachIndexed { index, uri ->
                val art = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull() ?: return@forEachIndexed

                val half = size / 2
                val x = (index % 2) * half
                val y = (index / 2) * half
                canvas.drawBitmap(
                    art,
                    Rect(0, 0, art.width, art.height),
                    Rect(x, y, x + half, y + half),
                    null
                )
            }

            if (artUris.size in 1..3) {
                val paint = Paint().apply {
                    color = 0xFF1A1A2E.toInt()
                }
                repeat(4 - artUris.size) { i ->
                    val index = artUris.size + i
                    val half = size / 2
                    val x = (index % 2) * half
                    val y = (index / 2) * half
                    canvas.drawRect(
                        Rect(x, y, x + half, y + half),
                        paint
                    )
                }
            }

            bitmap
        }
}
