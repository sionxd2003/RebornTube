package com.example.reborntube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun DeArrowContributionDialog(vm: MainViewModel) {
    val player = vm.activePlayer
    val durationSeconds = player.duration.takeIf { it > 0L }?.div(1000f) ?: 0f
    var title by remember(vm.current?.id) { mutableStateOf("") }
    var includeThumbnail by remember(vm.current?.id) { mutableStateOf(false) }
    var timestamp by remember(vm.current?.id) {
        mutableStateOf((player.currentPosition.coerceAtLeast(0L) / 1000f).toString())
    }
    val parsedTimestamp = timestamp.toFloatOrNull()
    val validTimestamp = parsedTimestamp != null && parsedTimestamp >= 0f &&
        parsedTimestamp <= durationSeconds
    val valid = title.isNotBlank() || (includeThumbnail && validTimestamp)

    AlertDialog(
        onDismissRequest = vm::closeDeArrowContribution,
        title = { Text(tr("Proponer contenido a DeArrow")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("Envía manualmente un título alternativo, una miniatura o ambos. Las propuestas quedan sujetas a votos comunitarios y no se publican automáticamente en tu dispositivo.")
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(200) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Título alternativo (opcional)")) },
                    singleLine = true
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = includeThumbnail, onCheckedChange = { includeThumbnail = it })
                    Text(tr("Proponer miniatura del momento actual"))
                }
                if (includeThumbnail) {
                    OutlinedTextField(
                        value = timestamp,
                        onValueChange = { timestamp = it.filter { char -> char.isDigit() || char == '.' } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("Tiempo de miniatura (segundos)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    TextButton(onClick = {
                        timestamp = (player.currentPosition.coerceAtLeast(0L) / 1000f).toString()
                    }) {
                        Text(tr("Usar posición actual"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !vm.deArrowActionLoading,
                onClick = {
                    vm.submitDeArrowContribution(
                        title = title.takeIf(String::isNotBlank),
                        thumbnailTimestamp = if (includeThumbnail) parsedTimestamp else null
                    )
                }
            ) {
                Text(if (vm.deArrowActionLoading) tr("Enviando…") else tr("Enviar"))
            }
        },
        dismissButton = {
            TextButton(onClick = vm::closeDeArrowContribution) { Text(tr("Cancelar")) }
        }
    )
}
