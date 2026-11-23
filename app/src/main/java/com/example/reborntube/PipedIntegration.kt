package com.example.reborntube

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PipedInstance(val name: String, val apiUrl: String)

data class PipedAccount(val username: String, val token: String)

object PipedRepository {
    const val DEFAULT_API_URL = "https://api.piped.private.coffee"
    private const val LEGACY_DEFAULT_API_URL = "https://pipedapi.kavin.rocks"
    val defaultInstances = listOf(
        PipedInstance("private.coffee (recomendada)", DEFAULT_API_URL),
        PipedInstance("ducks.party", "https://pipedapi.ducks.party"),
        PipedInstance("kavin.rocks (Official)", LEGACY_DEFAULT_API_URL),
        PipedInstance("leptons.xyz", "https://pipedapi.leptons.xyz"),
        PipedInstance("nosebs.ru", "https://pipedapi.nosebs.ru"),
        PipedInstance("kavin.rocks libre", "https://pipedapi-libre.kavin.rocks"),
        PipedInstance("privacy.com.de", "https://piped-api.privacy.com.de"),
        PipedInstance("adminforge.de", "https://pipedapi.adminforge.de")
    )
    private const val INSTANCE_LIST_URL =
        "https://raw.githubusercontent.com/TeamPiped/documentation/main/content/docs/public-instances/index.md"
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun availableInstances(): List<PipedInstance> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(INSTANCE_LIST_URL).header("Accept", "text/plain").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("No se pudieron consultar las instancias (HTTP ${response.code})")
            val markdown = response.body?.string().orEmpty()
            val rows = markdown.lineSequence().mapNotNull { line ->
                val cells = line.trim().trim('|').split('|').map(String::trim)
                if (cells.size < 2 || !cells[1].startsWith("https://")) return@mapNotNull null
                val apiUrl = normalizeApiUrl(cells[1])
                PipedInstance(cells[0], apiUrl)
            }.distinctBy { it.apiUrl }
            rows.toList().ifEmpty { error("La lista no contiene instancias válidas") }
        }
    }

    suspend fun authenticate(apiUrl: String, username: String, password: String, register: Boolean): PipedAccount =
        withContext(Dispatchers.IO) {
            val base = normalizeApiUrl(apiUrl)
            val body = JSONObject().put("username", username).put("password", password)
                .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$base/${if (register) "register" else "login"}")
                .post(body)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent())
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val payload = runCatching { JSONObject(text) }.getOrNull()
                val token = payload?.optString("token")?.takeIf { it.isNotBlank() && it != "null" }
                if (!response.isSuccessful || token == null) {
                    error(authenticationError(response.code, payload, text))
                }
                PipedAccount(username, token)
            }
        }

    suspend fun accountFeed(apiUrl: String, token: String): List<VideoCard> = withContext(Dispatchers.IO) {
        val base = normalizeApiUrl(apiUrl).toHttpUrlOrNull() ?: error("URL Piped inválida")
        val url = base.newBuilder().addPathSegments("feed").addQueryParameter("authToken", token).build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent())
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                error(message?.takeIf(String::isNotBlank) ?: "No se pudo cargar el feed Piped (HTTP ${response.code})")
            }
            val videos = JSONArray(text)
            buildList {
                for (index in 0 until videos.length()) {
                    val item = videos.optJSONObject(index) ?: continue
                    if (item.optString("type").isNotBlank() &&
                        item.optString("type") !in setOf("stream", "video")
                    ) continue
                    val rawUrl = item.optString("url")
                    val videoUrl = when {
                        rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                        rawUrl.startsWith("/") -> "https://www.youtube.com$rawUrl"
                        rawUrl.isNotBlank() -> "https://www.youtube.com/watch?v=$rawUrl"
                        else -> continue
                    }
                    val title = item.optString("title").takeIf(String::isNotBlank) ?: continue
                    add(
                        VideoCard(
                            url = videoUrl,
                            title = title,
                            channel = item.optString("uploaderName"),
                            channelUrl = item.optString("uploaderUrl").takeIf(String::isNotBlank)?.let {
                                if (it.startsWith("http")) it else "https://www.youtube.com$it"
                            },
                            thumb = item.optString("thumbnail").takeIf(String::isNotBlank),
                            duration = item.optLong("duration", 0L),
                            isLive = item.optBoolean("livestream", false),
                            views = item.optLong("views", -1L),
                            uploaded = item.optString("uploadedDate").takeIf(String::isNotBlank)
                        )
                    )
                }
            }
        }
    }

    fun normalizeApiUrl(value: String): String {
        val parsed = value.trim().trimEnd('/').toHttpUrlOrNull()
            ?: error("Introduce una dirección HTTPS válida de la API Piped.")
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.query == null && parsed.fragment == null
        ) { "La instancia debe ser HTTPS y no debe incluir credenciales, consulta ni fragmento." }
        return parsed.newBuilder().encodedPath(parsed.encodedPath.trimEnd('/').ifEmpty { "/" }).build()
            .toString().trimEnd('/')
    }

    fun migrateLegacyDefault(apiUrl: String, hasSavedSession: Boolean): String {
        val normalized = normalizeApiUrl(apiUrl)
        return if (!hasSavedSession && normalized == LEGACY_DEFAULT_API_URL) DEFAULT_API_URL else normalized
    }

    private fun userAgent() = "RebornTube/${BuildConfig.VERSION_NAME} (Android)"

    private fun authenticationError(code: Int, payload: JSONObject?, responseText: String): String {
        val detail = payload?.optString("error")?.takeIf(String::isNotBlank)
            ?: responseText.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }?.take(240)
        if (code == 403) {
            if (detail != null) {
                return trf(
                    "La instancia Piped denegó el acceso (HTTP 403): %s Prueba otra instancia; las cuentas no se comparten entre servidores.",
                    detail
                )
            }
            return tr("La instancia Piped denegó el acceso (HTTP 403). Prueba otra instancia; las cuentas no se comparten entre servidores.")
        }
        return detail ?: trf("La instancia rechazó la solicitud (HTTP %s).", code)
    }
}

class PipedTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("piped_auth", Context.MODE_PRIVATE)

    val hasToken: Boolean get() = prefs.contains(TOKEN_KEY)
    var username: String
        get() = prefs.getString(USERNAME_KEY, "").orEmpty()
        private set(value) { prefs.edit().putString(USERNAME_KEY, value).apply() }

    fun save(account: PipedAccount) {
        val encrypted = encrypt(account.token)
        prefs.edit()
            .putString(TOKEN_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(USERNAME_KEY, account.username)
            .apply()
    }

    fun token(): String? {
        val stored = prefs.getString(TOKEN_KEY, null) ?: return null
        return try {
            decrypt(Base64.decode(stored, Base64.NO_WRAP))
        } catch (error: Exception) {
            throw IllegalStateException("No se pudo descifrar la sesión Piped guardada. Cierra sesión y vuelve a entrar.", error)
        }
    }

    fun clear() {
        prefs.edit().remove(TOKEN_KEY).remove(USERNAME_KEY).apply()
    }

    private fun encrypt(value: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return cipher.iv + ciphertext
    }

    private fun decrypt(value: ByteArray): String {
        require(value.size > IV_SIZE) { "Invalid encrypted token" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_SIZE_BITS, value.copyOfRange(0, IV_SIZE))
        )
        return cipher.doFinal(value.copyOfRange(IV_SIZE, value.size)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val TOKEN_KEY = "encrypted_token"
        private const val USERNAME_KEY = "username"
        private const val KEY_ALIAS = "reborntube_piped_auth"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_SIZE_BITS = 128
    }
}
