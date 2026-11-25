package com.fintrack.app.core.common

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FileReadResult {
    data class Text(val text: String) : FileReadResult
    data object TooLarge : FileReadResult
    data object Unreadable : FileReadResult
}

/** Reads a user-picked document as text. Abstracted so the import flow can be tested without a ContentResolver. */
fun interface DocumentTextReader {
    suspend fun read(uri: String): FileReadResult
}

/**
 * Reads a Storage Access Framework document. Size is capped before decoding; text is UTF-8, or
 * Windows-1252 when the bytes are not valid UTF-8 (older spreadsheet exports).
 */
@Singleton
class ContentDocumentTextReader @Inject constructor(@ApplicationContext private val context: Context) : DocumentTextReader {

    override suspend fun read(uri: String): FileReadResult = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri.toUri()) ?: return@withContext FileReadResult.Unreadable
            val bytes = stream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    output.write(chunk, 0, count)
                    if (output.size() > MAX_BYTES) return@withContext FileReadResult.TooLarge
                }
                output.toByteArray()
            }
            FileReadResult.Text(decode(bytes))
        } catch (error: FileNotFoundException) {
            FileReadResult.Unreadable
        } catch (error: SecurityException) {
            // Permission to the picked document was revoked before it was read.
            FileReadResult.Unreadable
        } catch (error: IOException) {
            FileReadResult.Unreadable
        }
    }

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024

        fun decode(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }
}
