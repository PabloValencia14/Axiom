package org.readera.openreadera.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream

internal class DriveRemoteBlobStore(
    private val context: Context,
    private val safTreeUri: Uri?,
    private val oauthToken: String?,
    private val targetFolderName: String,
    private val client: OkHttpClient,
    private val resolveFolderId: suspend (String) -> String
) : DriveBlobStore {
    private val resolver = context.contentResolver
    private var resolvedFolderId: String? = null

    override suspend fun contains(name: String): Boolean {
        requireSafeName(name)
        val folder = safFolder() ?: return findRestFileId(name) != null
        return folder.findFile(name) != null
    }

    override suspend fun upload(
        name: String,
        mimeType: String,
        source: DriveAttachmentSource,
        expectedSha256: String
    ) {
        requireSafeName(name)
        require(name.startsWith("openreadera_blob_"))
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        val folder = safFolder()
        if (folder != null) {
            check(folder.canWrite()) { "La carpeta de Drive no permite escribir" }
            val temporary = folder.createFile(mimeType, "$name.tmp-${System.nanoTime()}")
                ?: error("No se pudo crear el archivo temporal en Drive")
            try {
                val output = resolver.openOutputStream(temporary.uri, "wt")
                    ?: error("No se pudo abrir el archivo temporal en Drive")
                output.use { DriveAttachmentPipeline.copyVerified(source, it, expectedSha256) }
                val existing = folder.findFile(name)
                if (existing != null) {
                    check(temporary.delete()) { "No se pudo limpiar el archivo temporal de Drive" }
                    return
                }
                check(temporary.renameTo(name)) { "No se pudo finalizar el archivo en Drive" }
            } catch (failure: Exception) {
                temporary.delete()
                throw failure
            }
            return
        }

        val token = validToken()
        val folderId = driveFolderId()
        val metadata = JSONObject().apply {
            put("name", name)
            put("mimeType", mimeType)
            if (folderId != "root") put("parents", JSONArray().put(folderId))
            put("appProperties", JSONObject().put("sha256", expectedSha256))
        }
        val mediaBody = object : RequestBody() {
            override fun contentType() = mimeType.toMediaType()
            override fun contentLength() = source.size
            override fun writeTo(sink: BufferedSink) {
                DriveAttachmentPipeline.copyVerified(source, sink.outputStream(), expectedSha256)
            }
        }
        val boundary = "-------OpenReadEraBlobBoundary"
        val multipart = MultipartBody.Builder(boundary)
            .setType("multipart/related".toMediaType())
            .addPart(
                Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                metadata.toString().toRequestBody("application/json".toMediaType())
            )
            .addPart(Headers.headersOf("Content-Type", mimeType), mediaBody)
            .build()
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) error("Google Drive rechazó el archivo (${response.code})")
        }
    }

    override suspend fun download(name: String): InputStream? {
        requireSafeName(name)
        val folder = safFolder()
        if (folder != null) {
            val document = folder.findFile(name) ?: return null
            return resolver.openInputStream(document.uri)
                ?: error("No se pudo leer el archivo de Drive")
        }
        val fileId = findRestFileId(name) ?: return null
        return downloadRestFile(fileId)
    }

    override suspend fun downloadLegacy(name: String): InputStream? {
        requireSafeName(name)
        val folder = safFolder()
        if (folder != null) {
            val document = folder.findFile(name) ?: return null
            return resolver.openInputStream(document.uri)
                ?: error("No se pudo leer el archivo heredado de Drive")
        }
        val fileId = findRestFileId(name) ?: return null
        return downloadRestFile(fileId)
    }

    private fun safFolder(): DocumentFile? = safTreeUri?.let {
        DocumentFile.fromTreeUri(context, it)
            ?: throw IllegalStateException("La carpeta SAF de sincronización ya no está disponible")
    }

    private suspend fun findRestFileId(name: String): String? {
        val token = validToken()
        val folderId = driveFolderId()
        val escapedName = escapeDriveQuery(name)
        val escapedFolder = escapeDriveQuery(folderId)
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'$escapedFolder' in parents and name='$escapedName' and trashed=false")
            .addQueryParameter("pageSize", "1")
            .addQueryParameter("fields", "files(id,name,size,appProperties)")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return execute(request).use { response ->
            if (!response.isSuccessful) error("Error consultando un archivo de Drive (${response.code})")
            JSONObject(response.body?.string().orEmpty())
                .optJSONArray("files")?.takeIf { it.length() > 0 }
                ?.getJSONObject(0)?.optString("id")?.takeIf(String::isNotBlank)
        }
    }

    private suspend fun downloadRestFile(fileId: String): InputStream {
        val token = validToken()
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val response = execute(request)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            error("Error descargando un archivo de Drive ($code)")
        }
        val body = response.body
        if (body == null) {
            response.close()
            error("Drive devolvió un archivo vacío")
        }
        return object : FilterInputStream(body.byteStream()) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    response.close()
                }
            }
        }
    }

    private suspend fun driveFolderId(): String {
        resolvedFolderId?.let { return it }
        return resolveFolderId(targetFolderName).also { resolvedFolderId = it }
    }

    private fun validToken(): String = oauthToken?.takeIf { it.startsWith("ya29") }
        ?: error("No hay una sesión OAuth de Drive activa")

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    private fun requireSafeName(name: String) {
        require(name.isNotBlank() && name.length <= 240 && name.none(Char::isISOControl)) {
            "Nombre de archivo remoto no válido"
        }
    }

    private fun escapeDriveQuery(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")
}
