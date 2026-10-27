package com.volla.accessories.fw.updater.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class FirmwareService(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun manifest(accessory: Accessory, channel: String = "stable"): FirmwareManifest =
        withContext(io) {
            val body = JSONObject()
                .put("moduleId", accessory.moduleId)
                .put("productId", accessory.productId)
                .put("hwRevision", accessory.hardwareRevision)
                .put("channel", channel)
            parseManifest(JSONObject(postJson("$baseUrl/getFirmware", body.toString())))
        }

    suspend fun download(release: FirmwareRelease, onProgress: (Float) -> Unit = {}): FirmwarePackage =
        withContext(io) {
            val connection = open(release.url)
            try {
                connection.connect()
                requireSuccess(connection, release.url)
                val total = if (release.sizeBytes > 0) release.sizeBytes else connection.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                val sink = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var transferred = 0L
                connection.inputStream.use { input ->
                    var read = input.read(buffer)
                    while (read >= 0) {
                        sink.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        transferred += read
                        if (total > 0) onProgress((transferred.toFloat() / total).coerceIn(0f, 1f))
                        read = input.read(buffer)
                    }
                }
                val data = sink.toByteArray()
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (release.sha256.isNotEmpty() && !release.sha256.equals(actual, ignoreCase = true)) {
                    throw IOException("Checksum mismatch for ${release.version}")
                }
                FirmwarePackage(release, data)
            } finally {
                connection.disconnect()
            }
        }

    private fun postJson(url: String, body: String): String {
        val connection = open(url)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.connect()
            requireSuccess(connection, url)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }

    private fun requireSuccess(connection: HttpURLConnection, url: String) {
        if (connection.responseCode !in 200..299) {
            throw IOException("HTTP ${connection.responseCode} for $url")
        }
    }

    private fun parseManifest(json: JSONObject): FirmwareManifest {
        val array = json.optJSONArray("releases") ?: JSONArray()
        val releases = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            FirmwareRelease(
                version = FirmwareVersion(item.getString("version")),
                url = item.getString("url"),
                sizeBytes = item.optLong("size"),
                sha256 = item.optString("sha256"),
                minCurrentVersion = FirmwareVersion(item.optString("min_current_version", "0.0.0")),
                notes = item.optString("notes"),
                releasedAt = item.optString("released_at"),
            )
        }
        return FirmwareManifest(
            moduleId = json.optString("module"),
            productId = json.optString("product"),
            channel = json.optString("channel", "stable"),
            latest = FirmwareVersion(json.optString("latest", "0.0.0")),
            releases = releases,
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://fwupdater.volla.tech/api/v1"
        private const val TIMEOUT_MS = 20000
    }
}
