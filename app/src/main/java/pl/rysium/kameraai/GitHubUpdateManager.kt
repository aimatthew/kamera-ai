package pl.rysium.kameraai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class GitHubRelease(
    val version: String,
    val apkUrl: String,
    val releaseUrl: String
)

class GitHubUpdateManager(private val context: Context) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val repository: String = context.getString(R.string.github_repository)
        .trim()
        .removePrefix("https://github.com/")
        .trimEnd('/')

    val isConfigured: Boolean
        get() {
            val parts = repository.split('/')
            return parts.size == 2 &&
                parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) } &&
                !repository.startsWith("OWNER/", ignoreCase = true)
        }

    val repositoryUrl: String
        get() = "https://github.com/$repository"

    fun checkForUpdate(
        onSuccess: (GitHubRelease) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isConfigured) {
            onError(context.getString(R.string.github_repository_missing))
            return
        }

        executor.execute {
            try {
                val response = requestText(
                    "https://api.github.com/repos/$repository/releases/latest"
                )
                val json = JSONObject(response)
                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null

                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }

                if (apkUrl.isNullOrBlank()) {
                    throw IOException("Najnowsze wydanie nie zawiera pliku APK.")
                }

                val apkAddress = URL(apkUrl)
                if (apkAddress.protocol != "https" || apkAddress.host != "github.com") {
                    throw IOException("GitHub zwrócił nieprawidłowy adres pliku APK.")
                }

                onSuccess(
                    GitHubRelease(
                        version = json.getString("tag_name").removePrefix("v"),
                        apkUrl = apkUrl,
                        releaseUrl = json.getString("html_url")
                    )
                )
            } catch (error: Exception) {
                onError(error.message ?: "Nie udało się sprawdzić aktualizacji.")
            }
        }
    }

    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val remote = versionNumbers(remoteVersion)
        val current = versionNumbers(currentVersion)
        val count = maxOf(remote.size, current.size)

        for (index in 0 until count) {
            val remotePart = remote.getOrElse(index) { 0 }
            val currentPart = current.getOrElse(index) { 0 }
            if (remotePart != currentPart) return remotePart > currentPart
        }
        return false
    }

    fun downloadApk(
        release: GitHubRelease,
        onProgress: (Int) -> Unit,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            val updateDirectory = File(context.cacheDir, "updates")
            val temporaryFile = File(updateDirectory, "kamera-ai-update.part")

            try {
                updateDirectory.mkdirs()
                temporaryFile.delete()

                val connection = openConnection(release.apkUrl)
                val totalBytes = connection.contentLengthLong
                var copiedBytes = 0L
                var lastProgress = -1

                connection.inputStream.buffered().use { input ->
                    temporaryFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copiedBytes += read

                            if (totalBytes > 0) {
                                val progress = ((copiedBytes * 100) / totalBytes).toInt()
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
                connection.disconnect()

                if (temporaryFile.length() == 0L) {
                    throw IOException("Pobrany plik APK jest pusty.")
                }

                val safeVersion = release.version.replace(Regex("[^A-Za-z0-9_.-]"), "-")
                val apkFile = File(updateDirectory, "kamera-ai-$safeVersion.apk")
                apkFile.delete()
                if (!temporaryFile.renameTo(apkFile)) {
                    temporaryFile.copyTo(apkFile, overwrite = true)
                    temporaryFile.delete()
                }
                onSuccess(apkFile)
            } catch (error: Exception) {
                temporaryFile.delete()
                onError(error.message ?: "Nie udało się pobrać aktualizacji.")
            }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun requestText(address: String): String {
        val connection = openConnection(address)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(address: String): HttpURLConnection {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        val accept = if (connection.url.host == "api.github.com") {
            "application/vnd.github+json"
        } else {
            "application/octet-stream"
        }
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "Kamera-AI-Android")
        connection.connect()

        if (connection.responseCode !in 200..299) {
            val message = when (connection.responseCode) {
                403 -> "GitHub tymczasowo ograniczył liczbę zapytań. Spróbuj później."
                404 -> "Nie znaleziono publicznego wydania GitHub z plikiem APK."
                else -> "GitHub zwrócił błąd ${connection.responseCode}."
            }
            connection.disconnect()
            throw IOException(message)
        }
        return connection
    }

    private fun versionNumbers(version: String): List<Int> =
        Regex("\\d+").findAll(version).map { it.value.toIntOrNull() ?: 0 }.toList()
}
