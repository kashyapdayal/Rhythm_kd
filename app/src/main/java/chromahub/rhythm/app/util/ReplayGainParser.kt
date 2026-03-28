package chromahub.rhythm.app.util

import android.content.Context
import android.net.Uri
import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File

data class ReplayGainInfo(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null
) {
    val isValid: Boolean get() = trackGain != null || albumGain != null
}

object ReplayGainParser {
    private const val TAG = "ReplayGainParser"

    /**
     * Extracts ReplayGain metadata from a local file URI using jaudiotagger.
     * Returns null if no valid replay gain tags are found.
     */
    fun parseReplayGain(context: Context, uri: Uri): ReplayGainInfo? {
        if (uri.scheme != "file" && uri.scheme != null) {
            // Can only parse local files reliably directly with jaudiotagger
            // Alternatively, could read from MediaStore but ID3 tags for RG might not be exposed there
            val path = MediaUtils.getRealPathFromURI(context, uri)
            if (path == null) {
                Log.w(TAG, "Cannot resolve real path for ReplayGain parsing: $uri")
                return null
            }
            return extractFromPath(path)
        }
        
        return extractFromPath(uri.path ?: return null)
    }

    private fun extractFromPath(filePath: String): ReplayGainInfo? {
        try {
            val file = File(filePath)
            if (!file.exists()) return null

            val audioFile = AudioFileIO.read(file)
            val tag: Tag = audioFile.tag ?: return null

            // Try to extract using standard jaudiotagger FieldKeys if supported
            // Some formats store replaygain as TXXX custom frames (ID3v2) or Vorbis Comments
            
            // Note: jaudiotagger doesn't have standard FieldKey for replaygain out of the box in older versions,
            // but we can query standard custom keys if needed.
            
            var trackGain: Float? = null
            var albumGain: Float? = null
            var trackPeak: Float? = null
            var albumPeak: Float? = null

            // Helper to safely parse float
            fun parseGain(value: String): Float? {
                try {
                    // Remove " dB" suffix if present
                    val cleanValue = value.replace(Regex("(?i)\\s*db\\s*"), "").trim()
                    return cleanValue.toFloatOrNull()
                } catch (e: Exception) {
                    return null
                }
            }

            // TXXX/REPLAYGAIN_TRACK_GAIN, etc.
            // Iterate over all fields as fallback
            val fields = tag.fields
            while (fields.hasNext()) {
                val field = fields.next()
                val id = field.id.uppercase()
                val content = field.toString().uppercase()

                // Check contents or ID for replaygain identifiers
                if (id.contains("REPLAYGAIN") || content.contains("REPLAYGAIN")) {
                    when {
                        content.contains("TRACK_GAIN") -> trackGain = parseGain(extractValueFromField(content))
                        content.contains("ALBUM_GAIN") -> albumGain = parseGain(extractValueFromField(content))
                        content.contains("TRACK_PEAK") -> trackPeak = parseGain(extractValueFromField(content))
                        content.contains("ALBUM_PEAK") -> albumPeak = parseGain(extractValueFromField(content))
                    }
                }
            }

            val result = ReplayGainInfo(trackGain, albumGain, trackPeak, albumPeak)
            if (result.isValid) {
                Log.d(TAG, "Parsed ReplayGain: \$result for \$filePath")
                return result
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ReplayGain for \$filePath: \${e.message}")
            return null
        }
    }
    
    // Helper to extract the actual value from a jaudiotagger field representation
    private fun extractValueFromField(fieldContent: String): String {
        // TXXX tags often render as: Text="REPLAYGAIN_TRACK_GAIN"; Text=" -3.5 dB";
        // Or simply the value if it's a vorbis comment.
        val lastQuote = fieldContent.lastIndexOf('"')
        if (lastQuote != -1) {
            val secondLastQuote = fieldContent.lastIndexOf('"', lastQuote - 1)
            if (secondLastQuote != -1) {
                return fieldContent.substring(secondLastQuote + 1, lastQuote)
            }
        }
        
        // Fallback: take the last part after equals or colon
        val parts = fieldContent.split("=", ":", ";")
        return parts.lastOrNull()?.replace("\"", "") ?: fieldContent
    }
}
