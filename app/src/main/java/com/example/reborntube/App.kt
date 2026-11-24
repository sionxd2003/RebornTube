package com.example.reborntube

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Títulos, fechas, tendencias y sugerencias salen en el idioma del móvil
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        val savedRegion = settings.getString("content_region", null)
        val systemLocale = Locale.getDefault()
        val savedLanguage = settings.getString("content_language", "system")
        AppLanguage.overrideCode = savedLanguage ?: "system"
        val language = if (savedLanguage == "system") systemLocale.language
        else savedLanguage.orEmpty().takeIf { it.length == 2 || it == "pap" } ?: systemLocale.language
        val region = savedRegion?.takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?.let { Locale(language, it) }
            ?: Locale(language, systemLocale.country)
        val country = if (region.country.isNullOrEmpty()) ContentCountry.DEFAULT else ContentCountry(region.country)
        NewPipe.init(DownloaderImpl.initialize(this), Localization.fromLocale(region), country)
        ChannelUpdateWorker.schedule(this)
    }
}
