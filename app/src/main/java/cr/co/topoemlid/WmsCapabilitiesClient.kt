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
    fun load(serviceUrl: String, timeoutMs: Int = 10000): Result<List<WmsLayerOption>> = runCatching {
        val capabilitiesUrl = buildCapabilitiesUrl(serviceUrl)
        var lastError: Throwable? = null
        var parsed: List<WmsLayerOption>? = null

        repeat(if (serviceUrl.contains("siri.snitcr.go.cr", true)) 4 else 1) { attempt ->
            try {
                val conn = (URL(capabilitiesUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "TopoEmlid/0.3")
                    setRequestProperty("Accept", "application/xml,text/xml,*/*")
                }

                val finalUrl = conn.url.toString()
                if (finalUrl.contains("/Geoservicios/error", true)) {
                    conn.disconnect()
                    error("El SIRI redirigió temporalmente a su página de error.")
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
                    parsed = result.distinctBy { it.name }
                }
                conn.disconnect()
                return@repeat
            } catch (e: Throwable) {
                lastError = e
                if (attempt < 3 && serviceUrl.contains("siri.snitcr.go.cr", true)) {
                    Thread.sleep(1500)
                }
            }
        }

        parsed ?: throw (lastError ?: IllegalStateException("No se pudo leer GetCapabilities."))
    }

    private data class MutableLayer(
        var name: String? = null,
        var title: String? = null,
        val crs: MutableList<String> = mutableListOf()
    )

    private fun buildCapabilitiesUrl(raw: String): String {
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
        val siri = trimmed.contains("siri.snitcr.go.cr", ignoreCase = true)
        return prefix + "service=WMS&request=GetCapabilities" + if (siri) "&version=1.1.1" else ""
    }
}
