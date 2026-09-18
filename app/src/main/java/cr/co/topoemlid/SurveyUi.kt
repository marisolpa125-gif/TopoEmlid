package cr.co.topoemlid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

@Composable
fun SurveyScreen(
    project: TopoProject?,
    gnss: GnssStatus
) {
    var pointNumber by remember { mutableStateOf("1") }
    var code by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var antennaHeight by remember(project?.id, project?.antennaHeightM) {
        mutableStateOf((project?.antennaHeightM ?: 2.0).toString())
    }
    var seconds by remember { mutableStateOf("5") }
    var showMeasurePanel by remember { mutableStateOf(false) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapView(context).apply {
                    onCreate(null)
                    onStart()
                    onResume()
                    getMapAsync { map ->
                        mapRef = map
                        map.setStyle(
                            Style.Builder().fromUri("https://demotiles.maplibre.org/style.json")
                        )
                    }
                }
            }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(8.dp),
            tonalElevation = 4.dp
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(project?.name ?: "Sin proyecto activo", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (gnss.connected) {
                        "${gnss.solution} • Sat: ${gnss.satellites ?: "—"} • H: ${gnss.horizontalAccuracyM?.let { "%.3f m".format(it) } ?: "—"} • V: ${gnss.verticalAccuracyM?.let { "%.3f m".format(it) } ?: "—"}"
                    } else {
                        "No hay ningún receptor conectado"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(onClick = { mapRef?.animateCamera(CameraUpdateFactory.zoomIn()) }) { Text("+") }
            FloatingActionButton(onClick = { mapRef?.animateCamera(CameraUpdateFactory.zoomOut()) }) { Text("−") }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = { showMeasurePanel = true },
                text = { Text("Medir punto") }
            )
            ExtendedFloatingActionButton(
                onClick = { },
                text = { Text("Replanteo") }
            )
        }
    }

    if (showMeasurePanel) {
        ModalBottomSheet(onDismissRequest = { showMeasurePanel = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Observación GNSS", style = MaterialTheme.typography.headlineSmall)

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pointNumber,
                    onValueChange = { pointNumber = it },
                    label = { Text("Número de punto") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Código") },
                    supportingText = { Text("La biblioteca editable de códigos se añadirá aquí.") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = antennaHeight,
                        onValueChange = { antennaHeight = it },
                        label = { Text("Altura antena (m)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { seconds = it },
                        label = { Text("Segundos") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Estado GNSS", style = MaterialTheme.typography.titleMedium)
                        Text("Solución: ${if (gnss.connected) gnss.solution else "SIN RECEPTOR"}")
                        Text("Satélites: ${gnss.satellites ?: "—"}")
                        Text("Precisión H: ${gnss.horizontalAccuracyM?.let { "%.3f m".format(it) } ?: "—"}")
                        Text("Precisión V: ${gnss.verticalAccuracyM?.let { "%.3f m".format(it) } ?: "—"}")
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = project != null && gnss.connected,
                    onClick = { },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Iniciar medición")
                }

                if (!gnss.connected) {
                    Text(
                        "Conecte primero un receptor GNSS para guardar una observación real.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
