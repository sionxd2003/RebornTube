package com.example.reborntube

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servidor pequeño en el móvil para transmitir a la TV con buena calidad y directos.
 * El Chromecast no puede pedir directamente los vídeos de YouTube (necesita cabeceras CORS) ni
 * los flujos de alta calidad por separado, así que pasa por aquí: el móvil le da un manifiesto DASH
 * (vídeo + audio) o la lista HLS de un directo, y reenvía los trozos de YouTube añadiendo CORS.
 */
object CastProxy {
    private const val CHUNK = 10L * 1024 * 1024
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null

    @Volatile
    private var manifest: String? = null

    @Volatile
    var baseUrl: String = ""
        private set

    private val cors = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Headers" to "*",
        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
        "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges"
    )

    private val counter = AtomicInteger(0)

    /** Cuántos archivos ha pedido la TV a este servidor (si es 0, la TV no llega al móvil). */
    val requestCount: Int get() = counter.get()

    @Volatile
    var lastError: String? = null
        private set

    /** Arranca el servidor (si no lo estaba) y devuelve su dirección, o null si no hay red local. */
    @Synchronized
    fun start(context: Context): String? {
        val ip = wifiIp(context) ?: localIp() ?: return null
        var socket = server
        if (socket == null || socket.isClosed) {
            val created = runCatching { ServerSocket(0) }.getOrNull() ?: return null
            socket = created
            server = created
            pool.execute {
                while (!created.isClosed) {
                    try {
                        val client = created.accept()
                        pool.execute { handle(client) }
                    } catch (e: Exception) {
                        if (created.isClosed) break
                    }
                }
            }
        }
        counter.set(0)
        lastError = null
        baseUrl = "http://$ip:${socket.localPort}"
        return baseUrl
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    fun proxied(url: String): String = "$baseUrl/p?u=" + URLEncoder.encode(url, "UTF-8")

    /** Igual que proxied() pero la ruta acaba en .m3u8: algunos receptores DLNA deciden el tipo por la extensión. */
    fun hlsUrl(url: String): String = "$baseUrl/h.m3u8?u=" + URLEncoder.encode(url, "UTF-8")
    fun manifestUrl(): String = "$baseUrl/m.mpd"
    fun setManifest(text: String) {
        manifest = text
    }

    /** La IP del móvil en la Wi-Fi (no la de los datos móviles ni la de una VPN). */
    private fun wifiIp(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local) continue
            val props = cm.getLinkProperties(network) ?: continue
            for (link in props.linkAddresses) {
                val address = link.address
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    val host = address.hostAddress
                    if (host != null) return host
                }
            }
        }
        return null
    }

    private fun localIp(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (ni in interfaces) {
            if (!ni.isUp || ni.isLoopback) continue
            for (addr in ni.inetAddresses) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) {
                    val host = addr.hostAddress
                    if (host != null) return host
                }
            }
        }
        return null
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream().buffered()
            val out = BufferedOutputStream(client.getOutputStream())
            val requestLine = readLine(input) ?: return
            counter.incrementAndGet()
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            when {
                method == "OPTIONS" -> writeHead(out, 204, "No Content", mapOf("Content-Length" to "0"))
                path.startsWith("/m.mpd") -> {
                    val body = manifest?.toByteArray(Charsets.UTF_8)
                    if (body == null) {
                        writeHead(out, 404, "Not Found", mapOf("Content-Length" to "0"))
                    } else {
                        writeHead(
                            out, 200, "OK",
                            mapOf("Content-Type" to "application/dash+xml", "Content-Length" to body.size.toString())
                        )
                        if (method != "HEAD") out.write(body)
                    }
                }
                path.startsWith("/p") || path.startsWith("/h.m3u8") -> proxy(method, path, headers, out)
                else -> writeHead(out, 404, "Not Found", mapOf("Content-Length" to "0"))
            }
            out.flush()
        } catch (e: Exception) {
            // Cortes normales de la TV al saltar o parar; el resto se anota para verlo en pantalla
            if (e !is java.net.SocketException) lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        (cors + headers).forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun openUpstream(url: String, range: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", DownloaderImpl.USER_AGENT)
        if (range != null) conn.setRequestProperty("Range", range)
        return conn
    }

    /** Tipo de archivo: si YouTube no lo indica bien, se usa el parámetro "mime" de la dirección. */
    private fun fixedContentType(target: String, upstream: String?): String {
        val fromUrl = Regex("[?&]mime=([^&]+)").find(target)?.groupValues?.get(1)?.let { Uri.decode(it) }
        return when {
            upstream != null && !upstream.contains("octet-stream", ignoreCase = true) -> upstream
            fromUrl != null -> fromUrl
            target.substringBefore('?').endsWith(".ts") -> "video/MP2T"
            else -> upstream ?: "application/octet-stream"
        }
    }

    private fun proxy(method: String, path: String, headers: Map<String, String>, out: OutputStream) {
        val target = Uri.parse("http://x$path").getQueryParameter("u")
        if (target == null) {
            writeHead(out, 400, "Bad Request", mapOf("Content-Length" to "0"))
            return
        }

        // Los vídeos de YouTube se piden por trozos con el parámetro "range" (así no van limitados)
        val range = headers["range"]
        val isGoogleVideo = target.contains("googlevideo.com") && target.contains("videoplayback")
        val clen = Regex("[?&]clen=(\\d+)").find(target)?.groupValues?.get(1)?.toLongOrNull()
        val rangeMatch = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }

        if (isGoogleVideo && clen != null && (range == null || rangeMatch != null)) {
            proxyFile(method, target, clen, rangeMatch, out)
            return
        }

        val conn = openUpstream(target, range)
        try {
            val code = conn.responseCode
            if (code !in 200..299) lastError = "HTTP $code en ${URL(target).host}"
            val contentType = fixedContentType(target, conn.contentType)
            val isPlaylist = URL(target).path.endsWith(".m3u8") || contentType.contains("mpegurl", ignoreCase = true)

            if (isPlaylist && code in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val body = rewritePlaylist(text, URL(target)).toByteArray(Charsets.UTF_8)
                writeHead(
                    out, 200, "OK",
                    mapOf("Content-Type" to "application/vnd.apple.mpegurl", "Content-Length" to body.size.toString())
                )
                if (method != "HEAD") out.write(body)
                return
            }

            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val h = LinkedHashMap<String, String>()
            h["Content-Type"] = contentType
            h["Accept-Ranges"] = "bytes"
            conn.getHeaderField("Content-Length")?.let { h["Content-Length"] = it }
            conn.getHeaderField("Content-Range")?.let { h["Content-Range"] = it }
            writeHead(out, code, conn.responseMessage ?: "OK", h)
            if (method != "HEAD" && stream != null) stream.copyTo(out, 64 * 1024)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Archivo de googlevideo con tamaño conocido. Si la TV pide un rango, se le da un trozo (206);
     * si no pide ninguno (muchos receptores DLNA), se le envía el archivo entero pidiéndolo a YouTube por trozos.
     */
    private fun proxyFile(method: String, target: String, clen: Long, rangeMatch: MatchResult?, out: OutputStream) {
        val type = fixedContentType(target, null)
        val sep = if (target.contains("?")) "&" else "?"

        if (rangeMatch == null) {
            val h = linkedMapOf("Content-Type" to type, "Accept-Ranges" to "bytes", "Content-Length" to clen.toString())
            writeHead(out, 200, "OK", h)
            if (method == "HEAD") return
            var pos = 0L
            while (pos < clen) {
                val end = minOf(pos + CHUNK - 1, clen - 1)
                val conn = openUpstream("$target${sep}range=$pos-$end", null)
                try {
                    if (conn.responseCode !in 200..299) {
                        lastError = "HTTP ${conn.responseCode} en ${URL(target).host}"
                        return
                    }
                    conn.inputStream.copyTo(out, 64 * 1024)
                } finally {
                    conn.disconnect()
                }
                pos = end + 1
            }
            return
        }

        val start = rangeMatch.groupValues[1].toLong()
        val asked = rangeMatch.groupValues[2].toLongOrNull() ?: Long.MAX_VALUE
        val end = minOf(asked, start + CHUNK - 1, clen - 1)
        if (start >= clen) {
            writeHead(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$clen", "Content-Length" to "0"))
            return
        }
        val h = linkedMapOf(
            "Content-Type" to type, "Accept-Ranges" to "bytes",
            "Content-Length" to (end - start + 1).toString(), "Content-Range" to "bytes $start-$end/$clen"
        )
        writeHead(out, 206, "Partial Content", h)
        if (method == "HEAD") return
        val conn = openUpstream("$target${sep}range=$start-$end", null)
        try {
            if (conn.responseCode !in 200..299) {
                lastError = "HTTP ${conn.responseCode} en ${URL(target).host}"
                return
            }
            conn.inputStream.copyTo(out, 64 * 1024)
        } finally {
            conn.disconnect()
        }
    }

    /** Listas HLS (directos): cada dirección interna pasa también por este servidor. */
    private fun rewritePlaylist(text: String, base: URL): String {
        val uriAttr = Regex("URI=\"([^\"]+)\"")
        return sanitizeMaster(text).split("\n").joinToString("\n") { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> line
                line.startsWith("#") -> uriAttr.replace(line) { m ->
                    "URI=\"" + proxied(URL(base, m.groupValues[1]).toString()) + "\""
                }
                else -> proxied(URL(base, line.trim()).toString())
            }
        }
    }

    /**
     * La lista principal de un directo de YouTube trae variantes que la TV no necesita (solo audio, VP9, AV1,
     * resoluciones enormes, listas de fotogramas clave...). Se dejan solo las de vídeo H.264 + audio hasta 1080p,
     * que son las que reproduce cualquier Chromecast o TV.
     */
    private fun sanitizeMaster(text: String): String {
        if (!text.contains("#EXT-X-STREAM-INF")) return text
        val lines = text.split("\n").map { it.trimEnd('\r') }
        val header = ArrayList<String>()
        val variants = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            when {
                l.startsWith("#EXT-X-STREAM-INF") -> {
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                    if (j < lines.size) variants += l to lines[j]
                    i = j + 1
                }
                l.startsWith("#EXT-X-I-FRAME-STREAM-INF") || l.startsWith("#EXT-X-SESSION-DATA") -> i++
                else -> {
                    if (l.isNotBlank()) header += l
                    i++
                }
            }
        }
        val codecs = Regex("CODECS=\"([^\"]*)\"")
        val resolution = Regex("RESOLUTION=(\\d+)x(\\d+)")
        val good = variants.filter { (inf, _) ->
            val c = codecs.find(inf)?.groupValues?.get(1)
            val h = resolution.find(inf)?.groupValues?.get(2)?.toIntOrNull()
            (c == null || c.contains("avc1")) && (h == null || h <= 1080)
        }
        val chosen = good.ifEmpty { variants }
        return (header + chosen.flatMap { listOf(it.first, it.second) }).joinToString("\n") + "\n"
    }

    /**
     * Mira si el directo usa segmentos fMP4 (lleva #EXT-X-MAP) o MPEG-TS. true = fMP4, false = TS,
     * null = no se pudo saber. La TV necesita saberlo para algunos directos. Bloqueante: llamar fuera del hilo principal.
     */
    fun probeHlsFmp4(hlsUrl: String): Boolean? = runCatching {
        fun get(url: String): String {
            val conn = openUpstream(url, null)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            return try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
        }
        val master = sanitizeMaster(get(hlsUrl))
        val first = master.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
        val media = if (first != null) get(URL(URL(hlsUrl), first.trim()).toString()) else master
        media.contains("#EXT-X-MAP")
    }.getOrNull()
}

/**
 * Manifiesto DASH con el vídeo y el audio elegidos (para ver en la TV con la calidad del móvil).
 * Los datos de índice de cada flujo se leen por reflexión porque la librería los ha movido de sitio.
 */
object DashManifest {
    private fun call(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            target.javaClass.getMethod(name).invoke(target)
        } catch (e: Exception) {
            null
        }
    }

    private fun number(stream: Any, name: String): Long {
        (call(stream, name) as? Number)?.let { return it.toLong() }
        (call(call(stream, "getItagItem"), name) as? Number)?.let { return it.toLong() }
        return -1L
    }

    private fun text(stream: Any, name: String): String? {
        (call(stream, name) as? String)?.let { if (it.isNotEmpty()) return it }
        return (call(call(stream, "getItagItem"), name) as? String)?.takeIf { it.isNotEmpty() }
    }

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun build(info: StreamInfo, video: VideoStream, audio: AudioStream): String? {
        val vCodec = text(video, "getCodec") ?: return null
        val aCodec = text(audio, "getCodec") ?: return null
        val vInit = number(video, "getInitStart") to number(video, "getInitEnd")
        val vIndex = number(video, "getIndexStart") to number(video, "getIndexEnd")
        val aInit = number(audio, "getInitStart") to number(audio, "getInitEnd")
        val aIndex = number(audio, "getIndexStart") to number(audio, "getIndexEnd")
        if (vInit.second <= 0 || vIndex.second <= 0 || aInit.second <= 0 || aIndex.second <= 0) return null
        val duration = info.duration
        if (duration <= 0) return null

        val vBand = number(video, "getBitrate").takeIf { it > 0 } ?: 2_000_000L
        val aBand = number(audio, "getBitrate").takeIf { it > 0 } ?: (audio.averageBitrate * 1000L).takeIf { it > 0 } ?: 128_000L
        val fps = if (video.fps > 0) " frameRate=\"${video.fps}\"" else ""
        val vUrl = esc(CastProxy.proxied(video.content))
        val aUrl = esc(CastProxy.proxied(audio.content))

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" ")
            append("type=\"static\" mediaPresentationDuration=\"PT${duration}S\" minBufferTime=\"PT2S\">\n")
            append("<Period>\n")
            append("<AdaptationSet mimeType=\"video/mp4\" subsegmentAlignment=\"true\" startWithSAP=\"1\">\n")
            append("<Representation id=\"video\" codecs=\"${esc(vCodec)}\" bandwidth=\"$vBand\" width=\"${video.width}\" height=\"${video.height}\"$fps>\n")
            append("<BaseURL>$vUrl</BaseURL>\n")
            append("<SegmentBase indexRange=\"${vIndex.first}-${vIndex.second}\"><Initialization range=\"${vInit.first}-${vInit.second}\"/></SegmentBase>\n")
            append("</Representation>\n</AdaptationSet>\n")
            append("<AdaptationSet mimeType=\"audio/mp4\" subsegmentAlignment=\"true\" startWithSAP=\"1\">\n")
            append("<Representation id=\"audio\" codecs=\"${esc(aCodec)}\" bandwidth=\"$aBand\">\n")
            append("<BaseURL>$aUrl</BaseURL>\n")
            append("<SegmentBase indexRange=\"${aIndex.first}-${aIndex.second}\"><Initialization range=\"${aInit.first}-${aInit.second}\"/></SegmentBase>\n")
            append("</Representation>\n</AdaptationSet>\n")
            append("</Period>\n</MPD>\n")
        }
    }
}
