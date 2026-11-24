package com.example.reborntube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCoverageTest {
    @Test
    fun papiamentoAndEnglishCoverCoreInterface() {
        val keys = listOf(
            "Ajustes", "Inicio", "Biblioteca", "Suscripciones", "Directos",
            "Novedades", "Tendencias", "Buscar", "Idioma de la app y contenido",
            "Más recientes", "Populares", "Más antiguos", "Comentarios",
            "Guardar", "Compartir", "Historial", "Apariencia", "Actualizaciones",
            "Nuevo video", "Videos nuevos", "Papiamento", "Mi música", "Buscar en mi música",
            "Reproducir todo", "Añadidas recientemente",
            "Todas las canciones", "Descarga pendiente", "No se encontraron canciones."
        )
        AppLanguage.overrideCode = "pap"
        keys.forEach { key ->
            assertTrue("Falta clave Papiamento para: $key", tr(key).isNotBlank())
        }
        AppLanguage.overrideCode = "en"
        assertEquals("Settings", tr("Ajustes"))
        assertEquals("Home", tr("Inicio"))
        AppLanguage.overrideCode = "system"
    }
}
