package cr.co.topoemlid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NtripProfilesScreen(
    profiles: List<NtripProfile>,
    onProfilesChanged: (List<NtripProfile>) -> Unit
) {
    var editing by remember { mutableStateOf<NtripProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<NtripProfile?>(null) }

    if (creating || editing != null) {
        NtripProfileDialog(
            profile = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { saved ->
                val updated = if (editing == null) {
                    profiles + saved
                } else {
                    profiles.map { if (it.id == saved.id) saved else it }
                }
                onProfilesChanged(updated)
                creating = false
                editing = null
            }
        )
    }

    deleteCandidate?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Eliminar perfil NTRIP") },
            text = { Text("¿Desea eliminar “${p.name}”?") },
            confirmButton = {
                Button(onClick = {
                    onProfilesChanged(profiles.filterNot { it.id == p.id })
                    deleteCandidate = null
                }) { Text("Eliminar") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteCandidate = null }) { Text("Cancelar") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("NTRIP", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Perfiles de correcciones RTK", style = MaterialTheme.typography.bodySmall)
            }
            FilledIconButton(onClick = { creating = true }) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Estos perfiles se utilizan para recibir correcciones NTRIP. Puede crear varios y asignar el que corresponda a cada proyecto.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { creating = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("+ Agregar perfil NTRIP")
        }

        Spacer(Modifier.height(12.dp))

        if (profiles.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("No hay perfiles guardados", fontWeight = FontWeight.Bold)
                    Text("Pulse “Agregar perfil NTRIP” para configurar el primero.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        profiles.forEach { p ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.name, fontWeight = FontWeight.Bold)
                    Text("${p.host}:${p.port}", style = MaterialTheme.typography.bodySmall)
                    Text("Mountpoint: ${p.mountPoint.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                    Text("Usuario: ${p.username.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editing = p }) { Text("Editar") }
                        OutlinedButton(onClick = { deleteCandidate = p }) { Text("Eliminar") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NtripProfileDialog(
    profile: NtripProfile?,
    onDismiss: () -> Unit,
    onSave: (NtripProfile) -> Unit
) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name ?: "") }
    var host by remember(profile?.id) { mutableStateOf(profile?.host ?: "") }
    var port by remember(profile?.id) { mutableStateOf(profile?.port?.toString() ?: "2101") }
    var mountPoint by remember(profile?.id) { mutableStateOf(profile?.mountPoint ?: "") }
    var username by remember(profile?.id) { mutableStateOf(profile?.username ?: "") }
    var password by remember(profile?.id) { mutableStateOf(profile?.password ?: "") }

    var loadingMounts by remember { mutableStateOf(false) }
    var mountPoints by remember { mutableStateOf<List<NtripMountPoint>>(emptyList()) }
    var mountMenuOpen by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadMountPoints() {
        val p = port.toIntOrNull() ?: return
        if (host.isBlank()) return

        loadingMounts = true
        loadError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                NtripSourceTableClient.load(
                    host = host.trim(),
                    port = p,
                    username = username.trim(),
                    password = password
                )
            }
            loadingMounts = false
            result.onSuccess {
                mountPoints = it
                mountMenuOpen = true
            }.onFailure {
                loadError = it.message ?: "No se pudieron cargar los puntos de montaje."
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "Nuevo perfil de NTRIP" else "Editar perfil NTRIP") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del perfil") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        mountPoints = emptyList()
                        mountPoint = ""
                    },
                    label = { Text("Dirección") },
                    supportingText = { Text("Obligatorio") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = port,
                    onValueChange = {
                        port = it
                        mountPoints = emptyList()
                        mountPoint = ""
                    },
                    label = { Text("Puerto") },
                    supportingText = { Text("Obligatorio") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Nombre de usuario") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                ExposedDropdownMenuBox(
                    expanded = mountMenuOpen,
                    onExpandedChange = {
                        if (mountPoints.isNotEmpty()) mountMenuOpen = !mountMenuOpen
                    }
                ) {
                    OutlinedTextField(
                        value = mountPoint,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Punto de montaje") },
                        supportingText = {
                            Text(
                                if (mountPoint.isBlank()) "Obligatorio" else "Seleccionado"
                            )
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = mountMenuOpen)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = mountMenuOpen,
                        onDismissRequest = { mountMenuOpen = false }
                    ) {
                        mountPoints.forEach { mp ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(mp.name, fontWeight = FontWeight.Bold)
                                        val meta = listOf(mp.identifier, mp.format, mp.country)
                                            .filter { it.isNotBlank() }
                                            .joinToString(" • ")
                                        if (meta.isNotBlank()) {
                                            Text(meta, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                },
                                onClick = {
                                    mountPoint = mp.name
                                    mountMenuOpen = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { loadMountPoints() },
                    enabled = host.isNotBlank() && port.toIntOrNull() != null && !loadingMounts,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loadingMounts) "Cargando…" else "Cargar puntos de montaje")
                }

                loadError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() &&
                    host.isNotBlank() &&
                    port.toIntOrNull() != null &&
                    mountPoint.isNotBlank(),
                onClick = {
                    onSave(
                        NtripProfile(
                            id = profile?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 2101,
                            mountPoint = mountPoint.trim(),
                            username = username.trim(),
                            password = password
                        )
                    )
                }
            ) { Text("Guardar") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
