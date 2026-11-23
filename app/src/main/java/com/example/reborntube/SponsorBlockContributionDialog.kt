package com.example.reborntube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
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
fun SponsorBlockContributionDialog(vm: MainViewModel) {
    val player = vm.activePlayer
    val currentSeconds = (player.currentPosition.coerceAtLeast(0L) / 1000f)
    val durationSeconds = (player.duration.takeIf { it > 0L }?.div(1000f) ?: (currentSeconds + 30f))
    var start by remember(vm.current?.id) { mutableStateOf(currentSeconds.toString()) }
    var end by remember(vm.current?.id) {
        mutableStateOf((currentSeconds + 15f).coerceAtMost(durationSeconds).toString())
    }
    var category by remember(vm.current?.id) { mutableStateOf("sponsor") }
    var categoryMenu by remember { mutableStateOf(false) }
    val startValue = start.toFloatOrNull()
    val endValue = end.toFloatOrNull()
    val valid = startValue != null && endValue != null && startValue >= 0f &&
        endValue > startValue && endValue <= durationSeconds

    AlertDialog(
        onDismissRequest = vm::closeSponsorContribution,
        title = { Text(tr("Enviar segmento a SponsorBlock")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("Revisa los tiempos y la categoría. El envío solo ocurre cuando pulsas Enviar; SponsorBlock lo somete a moderación comunitaria.")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it.filter { char -> char.isDigit() || char == '.' } },
                        modifier = Modifier.weight(1f),
                        label = { Text(tr("Inicio (segundos)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = end,
                        onValueChange = { end = it.filter { char -> char.isDigit() || char == '.' } },
                        modifier = Modifier.weight(1f),
                        label = { Text(tr("Fin (segundos)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { start = (player.currentPosition.coerceAtLeast(0L) / 1000f).toString() }) {
                        Text(tr("Inicio = posición actual"))
                    }
                    TextButton(onClick = { end = (player.currentPosition.coerceAtLeast(0L) / 1000f).toString() }) {
                        Text(tr("Fin = posición actual"))
                    }
                }
                Box {
                    OutlinedButton(onClick = { categoryMenu = true }) {
                        Text(tr("Categoría") + ": " + sponsorCategoryLabel(category))
                    }
                    DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        SponsorBlock.availableCategories.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(sponsorCategoryLabel(option)) },
                                onClick = { category = option; categoryMenu = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !vm.sponsorActionLoading,
                onClick = {
                    vm.submitSponsorContribution(startValue!!, endValue!!, category)
                }
            ) {
                Text(if (vm.sponsorActionLoading) tr("Enviando…") else tr("Enviar"))
            }
        },
        dismissButton = {
            TextButton(onClick = vm::closeSponsorContribution) { Text(tr("Cancelar")) }
        }
    )
}

fun sponsorCategoryLabel(category: String): String = when (category) {
    "sponsor" -> tr("Patrocinios")
    "selfpromo" -> tr("Autopromoción")
    "interaction" -> tr("Recordatorios de interacción")
    "intro" -> tr("Introducciones")
    "outro" -> tr("Créditos y despedidas")
    "preview" -> tr("Avances")
    "filler" -> tr("Relleno")
    "music_offtopic" -> tr("Música fuera de tema")
    "exclusive_access" -> tr("Acceso exclusivo")
    else -> category
}
