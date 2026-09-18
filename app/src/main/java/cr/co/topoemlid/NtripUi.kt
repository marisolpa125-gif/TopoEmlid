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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Perfiles NTRIP", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Guarde aquí sus casters RTK y asígnelos luego a cada proyecto.")
            }
            Button(onClick = { creating = true }) { Text("+ Nuevo") }
        }

        Spacer(Modifier.height(12.dp))

        if (profiles.isEmpty()) {
            Text("No hay perfiles guardados.")
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "Nuevo perfil NTRIP" else "Editar perfil NTRIP") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre del perfil") })
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Servidor / IP") })
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Puerto") })
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = mountPoint, onValueChange = { mountPoint = it }, label = { Text("Mountpoint") })
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Usuario") })
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña") })
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && host.isNotBlank() && port.toIntOrNull() != null,
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
