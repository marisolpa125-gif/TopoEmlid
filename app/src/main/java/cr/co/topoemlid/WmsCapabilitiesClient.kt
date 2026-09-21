package cr.co.topoemlid

import android.util.Xml
import java.net.HttpURLConnection
import java.net.URL
import org.xmlpull.v1.XmlPullParser

data class WmsLayerOption(
    val name: String,
    val title: String,
    val crs: List<String>
)

object WmsCapabilitiesClient {
    fun load(serviceUrl: String, timeoutMs: Int = 8000): Result<List<WmsLayerOption>> = runCatching {
        val isSiri = serviceUrl.contains("siri.snitcr.go.cr/Geoservicios/wms", ignoreCase = true)
        val attempts = if (isSiri) 4 else 1
        var lastError: Throwable? = null

        repeat(attempts) { attempt ->
            try {
                val capabilitiesUrl = buildCapabilitiesUrl(serviceUrl, isSiri)
                val conn = (URL(capabilitiesUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("User-Agent", "TopoEmlid/0.3")
                    setRequestProperty("Accept", "application/xml,text/xml,*/*")
                    setRequestProperty("Cache-Control", "no-cache")
                }

                val code = conn.responseCode
                val finalUrl = conn.url.toString()
                if (code !in 200..299 || finalUrl.contains("/Geoservicios/error", ignoreCase = true)) {
                    error("SIRI respondió temporalmente con error (HTTP $code).")
                }

                conn.inputStream.use { input ->
                    val parser = Xml.newPullParser()
                    parser.setInput(input, null)

                    val result = mutableListOf<WmsLayerOption>()
                    var event = parser.eventType
                    val layerStack = mutableListOf<MutableLayer>()
                    var currentTag: String? = null

                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                currentTag = parser.name
                                if (parser.name.equals("Layer", true)) {
                                    layerStack.add(MutableLayer())
                                }
                            }

                            XmlPullParser.TEXT -> {
                                if (layerStack.isNotEmpty()) {
                                    val text = parser.text?.trim().orEmpty()
                                    if (text.isNotBlank()) {
                                        when {
                                            currentTag.equals("Name", true) && layerStack.last().name == null ->
                                                layerStack.last().name = text
                                            currentTag.equals("Title", true) && layerStack.last().title == null ->
                                                layerStack.last().title = text
                                            currentTag.equals("CRS", true) || currentTag.equals("SRS", true) ->
                                                layerStack.last().crs.add(text)
                                        }
                                    }
                                }
                            }

                            XmlPullParser.END_TAG -> {
                                if (parser.name.equals("Layer", true) && layerStack.isNotEmpty()) {
                                    val layer = layerStack.removeAt(layerStack.lastIndex)
                                    val name = layer.name
                                    if (!name.isNullOrBlank()) {
                                        result += WmsLayerOption(
                                            name = name,
                                            title = layer.title?.ifBlank { name } ?: name,
                                            crs = layer.crs.distinct()
                                        )
                                    }
                                }
                                currentTag = null
                            }
                        }
                        event = parser.next()
                    }

                    if (result.isEmpty()) error("El servicio respondió, pero no publicó capas WMS.")
                    return@runCatching result.distinctBy { it.name }
                }
            } catch (t: Throwable) {
                lastError = t
                if (attempt < attempts - 1 && isSiri) {
                    Thread.sleep(1500)
                }
            }
        }

        if (isSiri) {
            return@runCatching listOf(
                WmsLayerOption(
                    name = "catastro",
                    title = "Zona 1",
                    crs = listOf("EPSG:4326", "EPSG:3857", "EPSG:5367", "EPSG:8908")
                ),
                WmsLayerOption(
                    name = "catastro_aldia",
                    title = "Zona 2",
                    crs = listOf("EPSG:4326", "EPSG:3857", "EPSG:5367", "EPSG:8908")
                ),
                WmsLayerOption(
                    name = "vias_publicas",
                    title = "Vías públicas",
                    crs = listOf("EPSG:4326", "EPSG:3857", "EPSG:5367", "EPSG:8908")
                )
            )
        }

        throw (lastError ?: IllegalStateException("No respondió el servicio WMS."))
    }

    private data class MutableLayer(
        var name: String? = null,
        var title: String? = null,
        val crs: MutableList<String> = mutableListOf()
    )

    private fun buildCapabilitiesUrl(raw: String, isSiri: Boolean): String {
        val trimmed = raw.trim()
        val qIndex = trimmed.indexOf('?')
        val base = if (qIndex >= 0) trimmed.substring(0, qIndex) else trimmed
        val existing = if (qIndex >= 0) trimmed.substring(qIndex + 1) else ""

        val kept = existing
            .split('&')
            .filter { it.isNotBlank() }
            .filterNot {
                val key = it.substringBefore('=').trim().lowercase()
                key == "request" || key == "service" || key == "version"
            }

        val prefix = if (kept.isEmpty()) "$base?" else "$base?${kept.joinToString("&")}&"
        return if (isSiri) {
            prefix + "service=WMS&request=GetCapabilities&version=1.1.1"
        } else {
            prefix + "service=WMS&request=GetCapabilities"
        }
    }
}
