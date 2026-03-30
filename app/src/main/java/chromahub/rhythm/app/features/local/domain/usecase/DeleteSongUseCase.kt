package chromahub.rhythm.app.features.local.domain.usecase

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.features.local.data.repository.MusicRepository

sealed class DeleteResult {
    object Success : DeleteResult()
    data class RequiresPermission(val intentSender: PendingIntent) : DeleteResult()
    data class Error(val message: String) : DeleteResult()
}

class DeleteSongUseCase(
    private val context: Context,
    private val musicRepository: MusicRepository
) {
    suspend operator fun invoke(song: Song): DeleteResult {
        val songId = song.id.toLongOrNull() ?: return DeleteResult.Error("Invalid song ID")
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
        val contentResolver: ContentResolver = context.contentResolver

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: createDeleteRequest
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                DeleteResult.RequiresPermission(pendingIntent)
            } else {
                // API 29 and below: Just try to delete
                val deletedRows = contentResolver.delete(uri, null, null)
                if (deletedRows > 0) {
                    musicRepository.removeSong(song.id)
                    DeleteResult.Success
                } else {
                    DeleteResult.Error("File not found or couldn't be deleted.")
                }
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is android.app.RecoverableSecurityException) {
                // API 29: RecoverableSecurityException
                DeleteResult.RequiresPermission(e.userAction.actionIntent)
            } else {
                DeleteResult.Error(e.message ?: "Unknown error")
            }
        }
    }
}
