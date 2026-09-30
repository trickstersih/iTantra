package com.tactical.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.tactical.platform.speech.SpeechLanguagePreferences
import com.tactical.platform.speech.RoutingSpeechToText
import com.tactical.platform.speech.andr2.Andr2SttModelStore
import com.tactical.platform.speech.english.EnglishSttModelStore
import com.tactical.platform.speech.mms.MmsTtsEngine
import com.tactical.platform.speech.mms.MmsTtsLanguage
import com.tactical.platform.speech.mms.MmsTtsModelStore
import com.tactical.platform.speech.mms.MmsTtsPlaybackCoordinator
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class TacticalApplication : Application() {

    @Inject
    lateinit var speechLanguagePreferences: SpeechLanguagePreferences

    @Inject
    lateinit var ttsEngineProvider: Provider<MmsTtsEngine>

    @Inject
    lateinit var ttsModelStore: MmsTtsModelStore

    @Inject
    lateinit var routingSpeechToText: RoutingSpeechToText

    @Inject
    lateinit var andr2SttModelStore: Andr2SttModelStore

    @Inject
    lateinit var englishSttModelStore: EnglishSttModelStore

    @Inject
    lateinit var mmsTtsPlaybackPreferences: com.tactical.platform.speech.mms.MmsTtsPlaybackPreferences

    @Inject
    lateinit var mmsTtsPlaybackCoordinator: MmsTtsPlaybackCoordinator

    private val speechPreloadScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        cleanupSpeechExtractionCache()
        andr2SttModelStore.cleanupUnbundledModels()
        englishSttModelStore.cleanupUnbundledModels()
        ttsModelStore.cleanupUnbundledModels()
        mmsTtsPlaybackCoordinator.setMode(mmsTtsPlaybackPreferences.playbackMode)
        createNotificationChannels()
        preloadSpeechModels()
    }

    /**
     * Speech startup order matters for PTT responsiveness: preload the
     * currently selected STT backend first, then preload TTS.
     *
     * Only one STT backend is kept live at a time; the selected language is
     * resolved from the persisted outgoing-language preference.
     */
    private fun preloadSpeechModels() {
        speechPreloadScope.launch {
            runCatching {
                routingSpeechToText.preloadSelectedLanguage()
            }.onFailure { error ->
                android.util.Log.w(
                    "TacticalApplication",
                    "STT preload failed: " + (error.message ?: error.javaClass.simpleName)
                )
            }

            val language = MmsTtsLanguage.fromIsoCode(
                speechLanguagePreferences.selectedLanguageCode
            ) ?: return@launch

            runCatching {
                ttsEngineProvider.get().preload(language)
            }.onFailure { error ->
                android.util.Log.w(
                    "TacticalApplication",
                    "TTS preload failed: " + (error.message ?: error.javaClass.simpleName)
                )
            }
        }
    }

    /**
     * Speech model installers use app-private data for staging so large model
     * extraction does not inflate Android's disposable cache bucket. A process
     * kill during extraction can leave a partial staging directory behind, so
     * remove those directories on the next startup.
     */
    private fun cleanupSpeechExtractionCache() {
        val cache = cacheDir
        cache.listFiles()
            ?.filter {
                it.name.startsWith("tts_bundle_") ||
                    it.name.startsWith("tts_model_") ||
                    it.name.startsWith("moonshine_stt_") ||
                    it.name.startsWith("vosk_hi_bundle_")
            }
            ?.forEach { file ->
                runCatching { file.deleteRecursively() }
            }

        filesDir.listFiles()
            ?.filter {
                it.name.startsWith(".tts_model_staging_") ||
                    it.name.startsWith(".andr2_stt_staging_") ||
                    it.name.startsWith(".moonshine_stt_staging_") ||
                    it.name.startsWith(".vosk_hi_bundle_staging_")
            }
            ?.forEach { file ->
                runCatching { file.deleteRecursively() }
            }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val meshChannel = NotificationChannel(
                CHANNEL_MESH,
                "Itantra Mesh Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors tactical mesh network connections and incoming transmissions."
            }

            val messageChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Itantra Messages",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for incoming text and voice messages."
                setShowBadge(true)
            }

            val emergencyChannel = NotificationChannel(
                CHANNEL_EMERGENCY,
                "Itantra Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority distress alerts and squelch breaker announcements."
                enableVibration(true)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(meshChannel)
            manager.createNotificationChannel(messageChannel)
            manager.createNotificationChannel(emergencyChannel)
        }
    }

    companion object {
        const val CHANNEL_MESH = "channel_sentinel_mesh"
        const val CHANNEL_EMERGENCY = "channel_sentinel_emergency"
        const val CHANNEL_MESSAGES = "channel_itantra_messages"
    }
}
