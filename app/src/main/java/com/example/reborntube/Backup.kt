package com.example.reborntube

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Backup {
    private const val ENTRY = "reborntube-backup.json"
    private const val LEGACY_ENTRY = "adfreetube-backup.json" // copias hechas con el nombre anterior

    /** Guarda todo (me gusta, no me gusta, suscripciones, historial, más tarde, progreso) en un zip. */
    fun export(context: Context, library: VideoLibrary, uri: Uri) {
        val out = context.contentResolver.openOutputStream(uri) ?: error(tr("No se pudo abrir el archivo"))
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY))
            zip.write(library.exportJson().toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    /** Devuelve true si el zip tenía una copia de RebornTube. */
    fun import(context: Context, library: VideoLibrary, uri: Uri): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: error(tr("No se pudo abrir el archivo"))
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(ENTRY) || entry.name.endsWith(LEGACY_ENTRY)) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    library.importJson(JSONObject(text))
                    return true
                }
                entry = zip.nextEntry
            }
        }
        return false
    }

    /**
     * Importa suscripciones e historial desde un zip (o archivos sueltos) de Google Takeout.
     * No hay inicio de sesión: NewPipeExtractor no lo permite y la API oficial de YouTube
     * no expone el historial, así que se usa lo que Google te deja descargar de tu cuenta.
     * Devuelve (vídeos importados, canales importados).
     */
    fun importTakeout(context: Context, library: VideoLibrary, uri: Uri): Pair<Int, Int> {
        val raw = context.contentResolver.openInputStream(uri) ?: error(tr("No se pudo abrir el archivo"))
        val input = BufferedInputStream(raw)
        val videos = ArrayList<VideoCard>()
        val channels = ArrayList<ChannelCard>()

        input.use { stream ->
            stream.mark(4)
            val b0 = stream.read()
            val b1 = stream.read()
            stream.reset()

            if (b0 == 'P'.code && b1 == 'K'.code) {
                val zip = ZipInputStream(stream)
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory && name.contains("youtube")) {
                        when {
                            name.endsWith(".csv") -> channels += parseSubscriptions(zip)
                            name.endsWith(".json") && name.contains("histor") ->
                                videos += parseHistory(zip, HISTORY_LIMIT - videos.size)
                        }
                    }
                    entry = zip.nextEntry
                }
            } else {
                // Archivo suelto: decidir por el contenido
                stream.mark(64)
                val head = ByteArray(64)
                val n = stream.read(head)
                stream.reset()
                val first = String(head, 0, maxOf(n, 0), Charsets.UTF_8).trimStart().firstOrNull()
                if (first == '[') videos += parseHistory(stream, HISTORY_LIMIT)
                else channels += parseSubscriptions(stream)
            }
        }

        library.mergeHistory(videos.take(HISTORY_LIMIT))
        library.mergeSubscriptions(channels)
        return videos.size to channels.size
    }

    private const val HISTORY_LIMIT = 500

    // El CSV de suscripciones: id del canal, URL del canal, título (los títulos de columna dependen del idioma).
    private fun parseSubscriptions(input: InputStream): List<ChannelCard> {
        val out = ArrayList<ChannelCard>()
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8)) // no se cierra: es del zip
        while (true) {
            val line = reader.readLine() ?: break
            val p = splitCsv(line)
            if (p.size >= 3 && p[1].contains("youtube.com/channel/")) {
                out += ChannelCard(p[1].trim().replace("http://", "https://"), p[2].trim(), null, -1)
            }
        }
        return out
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
        }
        out.add(sb.toString())
        return out
    }

    // watch-history.json: lista de objetos con title, titleUrl y subtitles[{name,url}]
    private fun parseHistory(input: InputStream, limit: Int): List<VideoCard> {
        val out = ArrayList<VideoCard>()
        if (limit <= 0) return out
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8)) // no se cierra: es del zip
        try {
            reader.beginArray()
            while (reader.hasNext() && out.size < limit) {
                var title: String? = null
                var url: String? = null
                var channel = ""
                var channelUrl: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "title" -> title = readString(reader)
                        "titleUrl" -> url = readString(reader)
                        "subtitles" -> {
                            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                                reader.beginArray()
                                var first = true
                                while (reader.hasNext()) {
                                    reader.beginObject()
                                    var n = ""
                                    var u: String? = null
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "name" -> n = readString(reader) ?: ""
                                            "url" -> u = readString(reader)
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                    if (first) { channel = n; channelUrl = u; first = false }
                                }
                                reader.endArray()
                            } else reader.skipValue()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                val link = url
                if (link != null && link.contains("watch?v=")) {
                    val id = link.substringAfter("v=").substringBefore('&')
                    val clean = (title ?: "").replace(Regex("^(Watched|Has visto|Viste|Visto)\\s+"), "")
                    out += VideoCard(
                        url = "https://www.youtube.com/watch?v=$id",
                        title = clean,
                        channel = channel,
                        channelUrl = channelUrl,
                        thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                        duration = 0
                    )
                }
            }
        } catch (e: Exception) {
            // Si el archivo se corta, nos quedamos con lo leído hasta ahí
        }
        return out
    }

    private fun readString(r: JsonReader): String? =
        if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextString()
}
