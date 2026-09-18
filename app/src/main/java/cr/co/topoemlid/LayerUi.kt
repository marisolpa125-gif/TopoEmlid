package cr.co.topoemlid

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectLayersScreen(project: TopoProject?) {
    val context = LocalContext.current

    if (project == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Capas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Primero cree o abra un proyecto. Las capas se guardan por proyecto.")
        }
        return
    }

    val store = remember(project.id) { LayerStore(context) }
    val basemapStore = remember(project.id) { BasemapStore(context) }
    var selectedBasemap by remember(project.id) { mutableStateOf(basemapStore.selected(project.id)) }
    var mapboxToken by remember { mutableStateOf(basemapStore.mapboxToken()) }
    var layers by remember(project.id) { mutableStateOf(store.load(project.id)) }
    var library by remember { mutableStateOf(store.loadLibrary()) }
    var editing by remember { mutableStateOf<LayerItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var menuLayer by remember { mutableStateOf<LayerItem?>(null) }
    var deleteCandidate by remember { mutableStateOf<LayerItem?>(null) }

    LaunchedEffect(project.id) {
        val existingLibrary = store.loadLibrary()
        val missing = layers.filter { layer -> existingLibrary.none { it.id == layer.id } }
        if (missing.isNotEmpty()) {
            val merged = existingLibrary + missing.mapIndexed { index, layer ->
                layer.copy(visible = true, order = existingLibrary.size + index)
            }
            library = merged
            store.saveLibrary(merged)
        }
    }

    fun persist(updated: List<LayerItem>) {
        layers = updated.mapIndexed { index, item -> item.copy(order = index) }
        store.save(project.id, layers)
    }

    if (creating || editing != null) {
        LayerEditorDialog(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { saved ->
                val updated = if (editing == null) {
                    layers + saved
                } else {
                    layers.map { if (it.id == saved.id) saved else it }
                }
                persist(updated)

                val libraryUpdated = if (library.any { it.id == saved.id }) {
                    library.map { if (it.id == saved.id) saved.copy(visible = true) else it }
                } else {
                    library + saved.copy(visible = true, order = library.size)
                }
                library = libraryUpdated
                store.saveLibrary(libraryUpdated)

                creating = false
                editing = null
            }
        )
    }

    menuLayer?.let { layer ->
        AlertDialog(
            onDismissRequest = { menuLayer = null },
            title = { Text(layer.name) },
            text = {
                Column {
                    TextButton(onClick = { editing = layer; menuLayer = null }) { Text("Editar") }
                    TextButton(onClick = {
                        val index = layers.indexOfFirst { it.id == layer.id }
                        if (index > 0) {
                            val m = layers.toMutableList()
                            val item = m.removeAt(index)
                            m.add(index - 1, item)
                            persist(m)
                        }
                        menuLayer = null
                    }) { Text("Mover arriba") }
                    TextButton(onClick = {
                        val index = layers.indexOfFirst { it.id == layer.id }
                        if (index >= 0 && index < layers.lastIndex) {
                            val m = layers.toMutableList()
                            val item = m.removeAt(index)
                            m.add(index + 1, item)
                            persist(m)
                        }
                        menuLayer = null
                    }) { Text("Mover abajo") }
                    TextButton(onClick = { deleteCandidate = layer; menuLayer = null }) { Text("Eliminar") }
                }
            },
            confirmButton = {}
        )
    }

    deleteCandidate?.let { layer ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Eliminar capa") },
            text = { Text("¿Desea eliminar “${layer.name}”?") },
            confirmButton = {
                Button(onClick = {
                    persist(layers.filterNot { it.id == layer.id })
                    deleteCandidate = null
                }) { Text("Eliminar") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteCandidate = null }) { Text("Cancelar") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Capas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(project.name, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { creating = true }) { Text("+ Nueva capa") }
        }

        Spacer(Modifier.height(10.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Mapa base", fontWeight = FontWeight.Bold)
                BasemapType.entries.forEach { type ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedBasemap == type,
                            onClick = {
                                selectedBasemap = type
                                basemapStore.setSelected(project.id, type)
                            }
                        )
                        Text(type.label)
                    }
                }

                if (selectedBasemap == BasemapType.MAPBOX_STREETS ||
                    selectedBasemap == BasemapType.MAPBOX_SATELLITE
                ) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = mapboxToken,
                        onValueChange = {
                            mapboxToken = it
                            basemapStore.setMapboxToken(it)
                        },
                        label = { Text("Token público de Mapbox") },
                        supportingText = {
                            Text(
                                if (mapboxToken.isBlank())
                                    "Necesario para cargar Mapbox."
                                else
                                    "Token guardado."
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Text("Biblioteca global de capas", fontWeight = FontWeight.Bold)
        Text(
            "Estas capas quedan disponibles para todos los proyectos.",
            style = MaterialTheme.typography.bodySmall
        )

        val availableLibraryLayers = library.filter { lib -> layers.none { it.id == lib.id } }
        if (library.isEmpty()) {
            Text(
                "Aún no hay capas globales. Las capas nuevas que cree se guardarán aquí automáticamente.",
                style = MaterialTheme.typography.bodySmall
            )
        } else if (availableLibraryLayers.isEmpty()) {
            Text(
                "Todas las capas globales ya están agregadas a este proyecto.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            availableLibraryLayers.forEach { lib ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(lib.name, fontWeight = FontWeight.Bold)
                            Text(lib.type.label, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = {
                                persist(
                                    layers + lib.copy(
                                        visible = true,
                                        order = layers.size
                                    )
                                )
                            }
                        ) {
                            Text("Agregar")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Text("Capas del proyecto", fontWeight = FontWeight.Bold)

        if (layers.isEmpty()) {
            Text("No hay capas superpuestas en este proyecto. Puede agregarlas desde la biblioteca global o crear una nueva.")
        }

        layers.forEach { layer ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .combinedClickable(
                        onClick = {
                            persist(layers.map {
                                if (it.id == layer.id) it.copy(visible = !it.visible) else it
                            })
                        },
                        onLongClick = { menuLayer = layer }
                    )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(layer.name, fontWeight = FontWeight.Bold)
                            Text(layer.type.label, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = layer.visible,
                            onCheckedChange = { checked ->
                                persist(layers.map { if (it.id == layer.id) it.copy(visible = checked) else it })
                            }
                        )
                    }

                    if (!layer.url.isNullOrBlank()) Text("URL: ${layer.url}", style = MaterialTheme.typography.bodySmall)
                    if (!layer.layerName.isNullOrBlank()) Text("Capa: ${layer.layerName}", style = MaterialTheme.typography.bodySmall)
                    if (!layer.localUri.isNullOrBlank()) Text("Archivo local seleccionado", style = MaterialTheme.typography.bodySmall)
                    Text("Opacidad: ${(layer.opacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.height(6.dp))
                    Text("Mantenga presionado para editar, mover o eliminar.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LayerEditorDialog(
    initial: LayerItem?,
    onDismiss: () -> Unit,
    onSave: (LayerItem) -> Unit
) {
    val context = LocalContext.current
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var type by remember(initial?.id) { mutableStateOf(initial?.type ?: LayerType.WMS) }
    var url by remember(initial?.id) { mutableStateOf(initial?.url ?: "") }
    var layerName by remember(initial?.id) { mutableStateOf(initial?.layerName ?: "") }
    var styleName by remember(initial?.id) { mutableStateOf(initial?.styleName ?: "") }
    var imageFormat by remember(initial?.id) { mutableStateOf(initial?.imageFormat ?: "image/png") }
    var crs by remember(initial?.id) { mutableStateOf(initial?.crs ?: "EPSG:3857") }
    var transparent by remember(initial?.id) { mutableStateOf(initial?.transparent ?: true) }
    var opacity by remember(initial?.id) { mutableFloatStateOf(initial?.opacity ?: 1f) }
    var localUri by remember(initial?.id) { mutableStateOf(initial?.localUri) }
    var localFileName by remember(initial?.id) { mutableStateOf<String?>(null) }
    var typeMenu by remember { mutableStateOf(false) }
    var wmsLoading by remember { mutableStateOf(false) }
    var wmsOptions by remember { mutableStateOf<List<WmsLayerOption>>(emptyList()) }
    var wmsMenuOpen by remember { mutableStateOf(false) }
    var wmsError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            localUri = uri.toString()
            localFileName = uri.lastPathSegment
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nueva capa" else "Editar capa") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre de la capa") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                Box {
                    OutlinedButton(onClick = { typeMenu = true }) { Text("Tipo: ${type.label}") }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        LayerType.entries.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.label) },
                                onClick = { type = item; typeMenu = false }
                            )
                        }
                    }
                }

                if (type == LayerType.WMS || type == LayerType.WMTS || type == LayerType.XYZ) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL del servicio") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (type == LayerType.WMS || type == LayerType.WMTS) {
                    Spacer(Modifier.height(8.dp))

                    if (type == LayerType.WMS) {
                        Button(
                            onClick = {
                                if (url.isNotBlank()) {
                                    wmsLoading = true
                                    wmsError = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            WmsCapabilitiesClient.load(url)
                                        }
                                        wmsLoading = false
                                        result.onSuccess {
                                            wmsOptions = it
                                            wmsMenuOpen = true
                                        }.onFailure {
                                            wmsError = it.message ?: "No se pudieron cargar las capas WMS."
                                        }
                                    }
                                }
                            },
                            enabled = url.isNotBlank() && !wmsLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (wmsLoading) "Consultando servicio…" else "Cargar capas WMS")
                        }

                        wmsError?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (wmsOptions.isNotEmpty()) {
                            Box {
                                OutlinedButton(
                                    onClick = { wmsMenuOpen = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (layerName.isBlank()) "Seleccionar capa WMS"
                                        else "Capa: $layerName"
                                    )
                                }

                                DropdownMenu(
                                    expanded = wmsMenuOpen,
                                    onDismissRequest = { wmsMenuOpen = false }
                                ) {
                                    wmsOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(option.title, fontWeight = FontWeight.Bold)
                                                    Text(option.name, style = MaterialTheme.typography.bodySmall)
                                                }
                                            },
                                            onClick = {
                                                layerName = option.name
                                                if (name.isBlank()) name = option.title
                                                if (option.crs.any { it.equals("EPSG:3857", true) }) {
                                                    crs = "EPSG:3857"
                                                }
                                                wmsMenuOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = layerName,
                        onValueChange = { layerName = it },
                        label = { Text("Nombre técnico de capa / layer") },
                        supportingText = { Text("En WMS conviene cargar la lista y seleccionar el Name publicado por el servicio.") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = styleName,
                        onValueChange = { styleName = it },
                        label = { Text("Style (opcional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = imageFormat,
                        onValueChange = { imageFormat = it },
                        label = { Text("Formato de imagen") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = crs,
                        onValueChange = { crs = it },
                        label = { Text("CRS del servicio") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = transparent, onCheckedChange = { transparent = it })
                        Text("Transparente")
                    }
                }

                if (type == LayerType.LOCAL_FILE) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Text(if (localUri == null) "Seleccionar archivo" else "Cambiar archivo")
                    }
                    if (localUri != null) {
                        Text(localFileName ?: "Archivo seleccionado", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Opacidad: ${(opacity * 100).toInt()}%")
                Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0f..1f)
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() &&
                    (type == LayerType.LOCAL_FILE || type == LayerType.DRAWING || url.isNotBlank()),
                onClick = {
                    onSave(
                        LayerItem(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            type = type,
                            visible = initial?.visible ?: true,
                            opacity = opacity,
                            url = url.trim().ifBlank { null },
                            layerName = layerName.trim().ifBlank { null },
                            styleName = styleName.trim().ifBlank { null },
                            imageFormat = imageFormat.trim().ifBlank { "image/png" },
                            transparent = transparent,
                            crs = crs.trim().ifBlank { "EPSG:3857" },
                            localUri = localUri,
                            order = initial?.order ?: 0
                        )
                    )
                }
            ) { Text("Guardar") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
