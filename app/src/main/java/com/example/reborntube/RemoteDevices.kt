package com.example.reborntube

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.wifi.WifiManager
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.MulticastSocket
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONException
import org.json.JSONObject
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Una TV o un Fire TV que no es Google Cast.
 * - avTransportUrl: acepta una dirección de vídeo por DLNA/UPnP (necesita un receptor DLNA en el aparato).
 * - dialAppsUrl: lo encontramos por DIAL (los Fire TV lo anuncian siempre), sirve para abrir la app de YouTube.
 */
data class RemoteDevice(
    val host: String,
    val name: String,
    val manufacturer: String,
    val model: String,
    val avTransportUrl: String? = null,
    val avTransportType: String? = null,
    val dialAppsUrl: String? = null,
    val udn: String? = null
) {
    val isFireTv: Boolean
        get() = manufacturer.contains("amazon", true) || model.startsWith("AFT", true) ||
            name.contains("fire tv", true) || name.contains("firetv", true)
    val canPlayUrl: Boolean get() = avTransportUrl != null
}

/** Busca en la Wi-Fi los Fire TV y las TVs/receptores DLNA mientras está abierta la hoja de "Transmitir a". */
class DeviceScanner(private val context: Context) {
    val devices = mutableStateListOf<RemoteDevice>()
    private var job: Job? = null
    private val found = ConcurrentHashMap<String, RemoteDevice>()

    fun start(scope: CoroutineScope) {
        stop()
        found.clear()
        devices.clear()
        job = scope.launch(Dispatchers.IO) { scan() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun addFireTvByAddress(address: String): Boolean = withContext(Dispatchers.IO) {
        val host = address.trim()
        if (!isPrivateIpv4(host)) return@withContext false
        val location = "http://$host:8765/ssdp/device-desc.xml"
        val reachable = try {
            val connection = URL(location).openConnection() as HttpURLConnection
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            false
        }
        if (!reachable) return@withContext false
        describe(host, location)
        found[host]?.dialAppsUrl != null
    }

    private fun isPrivateIpv4(value: String): Boolean {
        val octets = value.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            octets[0] == 192 && octets[1] == 168 ||
            octets[0] == 172 && octets[1] in 16..31
    }

    private fun wifiNetwork(): Pair<Network, NetworkInterface>? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val active = connectivity.activeNetwork
        return connectivity.allNetworks
            .filter { network ->
                connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            .sortedByDescending { it == active }
            .mapNotNull { network ->
                val properties: LinkProperties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
                val name = properties.interfaceName ?: return@mapNotNull null
                val networkInterface = NetworkInterface.getByName(name) ?: return@mapNotNull null
                if (!networkInterface.isUp || networkInterface.isLoopback) return@mapNotNull null
                network to networkInterface
            }
            .firstOrNull()
    }

    private suspend fun scan() {
        // Android descarta los paquetes multicast si no se pide este bloqueo
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("reborntube-ssdp")?.apply { setReferenceCounted(false); acquire() }
        var socket: MulticastSocket? = null
        try {
            val selectedWifi = wifiNetwork()
            socket = MulticastSocket(null).apply {
                reuseAddress = true
                soTimeout = 700
                selectedWifi?.first?.bindSocket(this)
                selectedWifi?.second?.let { networkInterface = it }
                bind(InetSocketAddress(0))
            }
            val group = InetAddress.getByName("239.255.255.250")
            val targets = listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:dial-multiscreen-org:service:dial:1"
            )
            val buf = ByteArray(4096)
            while (currentCoroutineContext().isActive) {
                repeat(2) {
                    targets.forEach { st ->
                        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\nUSER-AGENT: Android RebornTube\r\n\r\n"
                        val bytes = msg.toByteArray()
                        runCatching { socket.send(DatagramPacket(bytes, bytes.size, group, 1900)) }
                    }
                    delay(250)
                }
                val until = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < until && currentCoroutineContext().isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    val location = header(text, "location") ?: continue
                    val host = packet.address.hostAddress ?: continue
                    if (found[host]?.let { it.avTransportUrl != null && it.dialAppsUrl != null } == true) continue
                    describe(host, location)
                }
                delay(3000)
            }
        } catch (e: Exception) {
            // sin Wi-Fi o sin permiso: la lista simplemente queda vacía
        } finally {
            runCatching { socket?.close() }
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private fun header(text: String, name: String): String? =
        text.lines().firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()

    /** Lee la descripción del aparato (nombre, fabricante, servicios) y lo añade o completa en la lista. */
    private suspend fun describe(host: String, location: String) {
        val (xml, appUrl) = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(location).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                try {
                    conn.inputStream.bufferedReader().use { it.readText() } to conn.getHeaderField("Application-URL")
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        } ?: return

        val name = tag(xml, "friendlyName") ?: host
        val manufacturer = tag(xml, "manufacturer") ?: ""
        val model = tag(xml, "modelName") ?: ""
        val base = tag(xml, "URLBase") ?: location
        val fireTv = manufacturer.contains("amazon", true) ||
            model.startsWith("AFT", true) ||
            name.contains("fire tv", true) || name.contains("firetv", true)
        val rebornTubeAppsUrl = if (fireTv) probeRebornTubeAppsUrl(host) else null
        val resolvedAppUrl = rebornTubeAppsUrl ?: appUrl

        var avUrl: String? = null
        var avType: String? = null
        Regex("<service>(.*?)</service>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(xml).forEach { m ->
                val type = tag(m.groupValues[1], "serviceType") ?: return@forEach
                if (type.contains("AVTransport", true)) {
                    avType = type
                    avUrl = tag(m.groupValues[1], "controlURL")?.let { runCatching { URL(URL(base), it).toString() }.getOrNull() }
                }
            }

        val previous = found[host]
        val merged = RemoteDevice(
            host = host,
            name = if (avUrl != null || previous == null) name else previous.name,
            manufacturer = manufacturer.ifBlank { previous?.manufacturer ?: "" },
            model = model.ifBlank { previous?.model ?: "" },
            avTransportUrl = avUrl ?: previous?.avTransportUrl,
            avTransportType = avType ?: previous?.avTransportType,
            dialAppsUrl = resolvedAppUrl?.trimEnd('/') ?: previous?.dialAppsUrl,
            udn = tag(xml, "UDN") ?: previous?.udn
        )
        found[host] = merged

        // Los Chromecast ya salen en la lista de Google Cast; aquí van Fire TV y TVs/receptores DLNA
        val show = merged.isFireTv ||
            (merged.avTransportUrl != null && !merged.manufacturer.contains("google", true))
        withContext(Dispatchers.Main) {
            val i = devices.indexOfFirst { it.host == host }
            when {
                !show -> if (i >= 0) devices.removeAt(i)
                i >= 0 -> devices[i] = merged
                else -> devices.add(merged)
            }
        }
    }

    private fun probeRebornTubeAppsUrl(host: String): String? {
        if (host.contains(':')) return null
        val description = URL("http://$host:8765/ssdp/device-desc.xml").openConnection() as HttpURLConnection
        description.connectTimeout = 1000
        description.readTimeout = 1000
        try {
            if (description.responseCode !in 200..299) return null
            val appsUrl = description.getHeaderField("Application-URL")?.trimEnd('/') ?: return null
            val app = URL("$appsUrl/RebornTube").openConnection() as HttpURLConnection
            app.connectTimeout = 1000
            app.readTimeout = 1000
            return try {
                if (app.responseCode !in 200..299) null
                else app.inputStream.bufferedReader().use { body ->
                    appsUrl.takeIf { body.readText().contains("RebornTube") }
                }
            } catch (_: IOException) {
                null
            } finally {
                app.disconnect()
            }
        } catch (_: IOException) {
            return null
        } finally {
            description.disconnect()
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>\\s*([^<]*?)\\s*</$name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")
}

/** Órdenes DLNA/UPnP (AVTransport) y DIAL. Todas son bloqueantes: llamarlas fuera del hilo principal. */
object Upnp {
    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun soap(device: RemoteDevice, action: String, args: String): String {
        val control = device.avTransportUrl ?: error("Sin control DLNA")
        val type = device.avTransportType ?: "urn:schemas-upnp-org:service:AVTransport:1"
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$type\"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
        val conn = URL(control).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", "\"$type#$action\"")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("DLNA $action: HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    fun setUri(device: RemoteDevice, url: String, mime: String, title: String, live: Boolean) {
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res></item></DIDL-Lite>"
        runCatching { soap(device, "Stop", "") }
        soap(
            device, "SetAVTransportURI",
            "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>"
        )
    }

    fun play(device: RemoteDevice) { soap(device, "Play", "<Speed>1</Speed>") }
    fun pause(device: RemoteDevice) { soap(device, "Pause", "") }
    fun stop(device: RemoteDevice) { soap(device, "Stop", "") }

    fun seek(device: RemoteDevice, positionMs: Long) {
        soap(device, "Seek", "<Unit>REL_TIME</Unit><Target>${formatTime(positionMs)}</Target>")
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT... */
    fun transportState(device: RemoteDevice): String? =
        tag(soap(device, "GetTransportInfo", ""), "CurrentTransportState")

    /** Posición actual y duración en milisegundos (-1 si el aparato no las da). */
    fun position(device: RemoteDevice): Pair<Long, Long> {
        val xml = soap(device, "GetPositionInfo", "")
        return parseTime(tag(xml, "RelTime")) to parseTime(tag(xml, "TrackDuration"))
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>\\s*([^<]*?)\\s*</$name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
    }

    private fun parseTime(text: String?): Long {
        if (text.isNullOrBlank() || text == "NOT_IMPLEMENTED") return -1
        val parts = text.substringBefore('.').split(":").mapNotNull { it.toLongOrNull() }
        if (parts.size != 3) return -1
        return (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
    }

    /**
     * Abre el vídeo en la app de YouTube del aparato (DIAL). Es la app oficial: lleva anuncios.
     * Sirve para los Fire TV que no tienen un receptor DLNA instalado.
     */
    fun launchYouTube(device: RemoteDevice, videoId: String): Boolean {
        val apps = device.dialAppsUrl ?: return false
        val conn = URL("$apps/YouTube").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write("v=$videoId".toByteArray()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    fun stopRebornTube(device: RemoteDevice, sessionToken: String): Boolean {
        val apps = device.dialAppsUrl ?: return false
        val conn = URL("${apps.trimEnd('/')}/RebornTube").openConnection() as HttpURLConnection
        return try {
            val payload = listOf("sessionToken" to sessionToken, "action" to "stop")
                .joinToString("&") { (key, value) ->
                    "${URLEncoder.encode(key, Charsets.UTF_8.name())}=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
                }
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: IOException) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Abre la app compañera mediante DIAL. El código solo empareja por primera vez; los envíos
     * siguientes usan el token de sesión que devuelve el receptor.
     */
    fun launchRebornTube(
        device: RemoteDevice,
        pairingCode: String?,
        sessionToken: String?,
        info: StreamInfo?,
        positionMs: Long = 0L,
        progressCallbackUrl: String? = null,
        progressToken: String? = null,
        fastVideoUrl: String? = null,
        fastAudioUrl: String? = null,
        fastHlsUrl: String? = null,
        fastQuality: String? = null
    ): FireTvLaunchResult {
        val apps = device.dialAppsUrl ?: return FireTvLaunchResult(null)
        val credential = sessionToken?.takeIf { it.isNotBlank() }?.let { "sessionToken" to it }
            ?: pairingCode?.takeIf { it.length == 6 }?.let { "code" to it }
            ?: throw IllegalArgumentException("Se necesita un código de emparejamiento o un token de sesión.")
        val fields = linkedMapOf(credential)
        if (info != null) {
            fields["videoId"] = info.id
            fields["videoUrl"] = info.url
            fields["title"] = info.name ?: ""
            fields["live"] = (
                info.streamType == StreamType.LIVE_STREAM ||
                    info.streamType == StreamType.AUDIO_LIVE_STREAM
                ).toString()
            if (positionMs > 0) fields["positionMs"] = positionMs.toString()
            if (!progressCallbackUrl.isNullOrBlank() && !progressToken.isNullOrBlank()) {
                fields["progressCallbackUrl"] = progressCallbackUrl
                fields["progressToken"] = progressToken
            }
            fastHlsUrl?.takeIf { it.startsWith("https://") }?.let {
                fields["fastHlsUrl"] = it
            } ?: run {
                fastVideoUrl?.takeIf { it.startsWith("https://") }?.let {
                    fields["fastVideoUrl"] = it
                }
                fastAudioUrl?.takeIf { it.startsWith("https://") }?.let {
                    fields["fastAudioUrl"] = it
                }
                fastQuality?.let { fields["fastQuality"] = it }
            }
        }
        val payload = fields.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, Charsets.UTF_8.name())}=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        val conn = URL("${apps.trimEnd('/')}/RebornTube").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                if (responseCode == 403 && sessionToken != null) {
                    throw FireTvSessionTokenRejectedException()
                }
                val message = when (responseCode) {
                    403 -> tr("El código no coincide con las seis cifras que muestra ahora el Fire TV.")
                    404 -> tr("No se encontró RebornTube en el Fire TV. Abre esa app y vuelve a intentarlo.")
                    else -> trf("El Fire TV rechazó la conexión (HTTP %s).", responseCode)
                }
                error(message)
            }
            val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
            val returnedToken = try {
                JSONObject(responseBody).optString("sessionToken").takeIf { it.isNotBlank() }
            } catch (_: JSONException) {
                null
            }
            FireTvLaunchResult(returnedToken)
        } finally {
            conn.disconnect()
        }
    }
}

data class FireTvLaunchResult(val sessionToken: String?)

class FireTvSessionTokenRejectedException : IOException()
