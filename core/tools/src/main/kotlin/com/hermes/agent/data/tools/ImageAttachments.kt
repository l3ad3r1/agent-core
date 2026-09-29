package com.hermes.agent.data.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.hermes.agent.util.net.PublicNetworkGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns an image reference (content URI, file path, data URL or public http(s) URL) into
 * bytes a model can take, downscaled to at most [MAX_DIMENSION] px on the long edge.
 *
 * Shared by `vision_analyze` and chat attachments. Chat used to hand the phone-local
 * `content://` URI itself to the provider as the image URL, which no provider can open.
 */
@Singleton
class ImageAttachments @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    networkGuard: PublicNetworkGuard,
) {
    private val downloadClient: OkHttpClient = networkGuard.restrict(
        okHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
    )

    /** A `data:` URL for [input], optimised for a vision model. */
    suspend fun toDataUrl(input: String): String {
        val (bytes, mime) = load(input)
        require(bytes.isNotEmpty()) { "empty image data" }
        val (optimized, finalMime) = optimize(bytes, mime)
        return "data:$finalMime;base64," + Base64.encodeToString(optimized, Base64.NO_WRAP)
    }

    suspend fun load(input: String): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        when {
            trimmed.startsWith("data:") -> {
                val commaIndex = trimmed.indexOf(',')
                val header = if (commaIndex > 0) trimmed.substring(0, commaIndex) else ""
                val mime = header.substringAfter("data:").substringBefore(";").takeIf { it.isNotBlank() } ?: "image/jpeg"
                val b64 = if (commaIndex > 0) trimmed.substring(commaIndex + 1) else trimmed
                Base64.decode(b64, Base64.DEFAULT) to mime
            }
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                // An image link from the model: public internet only.
                downloadClient.newCall(Request.Builder().url(trimmed).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code} downloading image from $trimmed" }
                    val mime = response.header("Content-Type")?.substringBefore(";")?.trim() ?: "image/jpeg"
                    val bytes = response.body?.bytes() ?: error("Empty response body from $trimmed")
                    bytes to mime
                }
            }
            trimmed.startsWith("content://") -> {
                val uri = Uri.parse(trimmed)
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Cannot open content URI: $trimmed")
                bytes to mime
            }
            else -> {
                val file = File(trimmed.removePrefix("file://"))
                require(file.exists() && file.canRead()) { "File not found or unreadable: ${file.path}" }
                val mime = when (file.extension.lowercase()) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> "image/jpeg"
                }
                file.readBytes() to mime
            }
        }
    }

    fun optimize(bytes: ByteArray, mimeType: String): Pair<ByteArray, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val origWidth = bounds.outWidth
        val origHeight = bounds.outHeight
        if (origWidth <= 0 || origHeight <= 0) return bytes to mimeType // not a decodable bitmap

        val longestSide = maxOf(origWidth, origHeight)
        if (longestSide <= MAX_DIMENSION && bytes.size <= MAX_BYTES) return bytes to mimeType

        var sampleSize = 1
        while ((longestSide / sampleSize) > MAX_DIMENSION) sampleSize *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?: return bytes to mimeType
        val scaled = if (maxOf(decoded.width, decoded.height) > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        val format = if (mimeType.contains("png", ignoreCase = true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val out = ByteArrayOutputStream()
        scaled.compress(format, 85, out)
        return out.toByteArray() to (if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg")
    }

    companion object {
        const val MAX_DIMENSION = 1568
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
