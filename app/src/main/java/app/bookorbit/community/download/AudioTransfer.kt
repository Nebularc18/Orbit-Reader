package app.bookorbit.community.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import okhttp3.Response

/** Keeps partial audio only when an HTTP validator makes a later append safe. */
class AudioTransfer {
    suspend fun download(
        file: File,
        expectedSize: Long?,
        request: (Long, String?) -> Response,
        checkActive: () -> Unit,
        progress: suspend (Long, Long?) -> Unit,
    ) {
        val partial = File(file.path + ".part")
        val validatorFile = File(file.path + ".part.etag")
        val validator =
            validatorFile
                .takeIf { it.isFile }
                ?.readText()
                ?.takeIf {
                    it.startsWith('"') &&
                        it.endsWith('"') &&
                        !it.contains('\n') &&
                        !it.contains('\r')
                }
        if (validator == null || (expectedSize != null && partial.length() > expectedSize)) {
            partial.delete()
            validatorFile.delete()
        }
        val offset = partial.length()
        checkActive()
        val response =
            try {
                request(offset, validator.takeIf { offset > 0 })
            } catch (error: app.bookorbit.community.data.ApiFailure) {
                if (error.status == 416) {
                    partial.delete()
                    validatorFile.delete()
                    throw IOException("Download range changed", error)
                }
                throw error
            }
        response.use {
            val body = response.body ?: throw IOException("No audio received")
            val append = response.code == 206 && offset > 0
            val range =
                response.header("Content-Range")?.let {
                    Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it)
                }
            val start = range?.groupValues?.get(1)?.toLongOrNull()
            val end = range?.groupValues?.get(2)?.toLongOrNull()
            val total =
                if (response.code == 206) range?.groupValues?.get(3)?.toLongOrNull()
                else body.contentLength().takeIf { it >= 0 }
            if (
                response.code == 206 &&
                    (start != offset ||
                        end == null ||
                        total == null ||
                        end < offset ||
                        end >= total ||
                        (body.contentLength() >= 0 && body.contentLength() != end - offset + 1) ||
                        (append && response.header("ETag") != validator))
            ) {
                partial.delete()
                validatorFile.delete()
                throw IOException("Invalid audio range response")
            }
            if (response.code != 200 && response.code != 206) throw IOException("No audio received")
            if (expectedSize != null && total != null && total != expectedSize) {
                partial.delete()
                validatorFile.delete()
                throw IOException("Audiobook size changed. Retry to refresh its files.")
            }
            if (!append) {
                partial.delete()
                validatorFile.delete()
            }
            response
                .header("ETag")
                ?.takeIf { it.startsWith('"') && it.endsWith('"') }
                ?.let { validatorFile.writeText(it) }
            var bytes = if (append) offset else 0L
            progress(bytes, total ?: expectedSize)
            body.byteStream().use { source ->
                FileOutputStream(partial, append).use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        val limit = total ?: expectedSize
                        if (limit != null && bytes > limit - count) {
                            throw IOException("Audio exceeded its expected size")
                        }
                        target.write(buffer, 0, count)
                        bytes += count
                        progress(bytes, total ?: expectedSize)
                    }
                }
            }
            checkActive()
            if (
                bytes <= 0 ||
                    (total != null && bytes != total) ||
                    (expectedSize != null && bytes != expectedSize)
            )
                throw IOException("Incomplete audio download")
            if (!partial.renameTo(file)) throw IOException("Could not finish download")
            validatorFile.delete()
        }
    }
}
