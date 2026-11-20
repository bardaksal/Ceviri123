package com.example.subtitle

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.roiDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "subcast_roi_preferences"
)

data class SubtitleRoiSettings(
    val roi: SubtitleRoi = SubtitleRoi.DEFAULT,
    val isUserConfigured: Boolean = false,
    val ocrIntervalMs: Long = DEFAULT_OCR_INTERVAL_MS
) {
    companion object {
        const val DEFAULT_OCR_INTERVAL_MS = 150L
        const val MIN_OCR_INTERVAL_MS = 100L
        const val MAX_OCR_INTERVAL_MS = 500L
    }
}

class RoiPreferencesRepository(private val context: Context) {

    val settingsFlow: Flow<SubtitleRoiSettings> = context.roiDataStore.data.map { prefs ->
        val rawLeft = prefs[KEY_LEFT]
        val rawTop = prefs[KEY_TOP]
        val rawRight = prefs[KEY_RIGHT]
        val rawBottom = prefs[KEY_BOTTOM]
        val aspect = prefs[KEY_ASPECT_RATIO]
        val configured = prefs[KEY_CONFIGURED] ?: false

        val roi = if (rawLeft != null && rawTop != null && rawRight != null && rawBottom != null) {
            val candidate = SubtitleRoi(
                left = rawLeft,
                top = rawTop,
                right = rawRight,
                bottom = rawBottom,
                referenceAspectRatio = aspect
            )
            if (candidate.isValid()) candidate else candidate.clamped()
        } else {
            SubtitleRoi.DEFAULT
        }

        val interval = (prefs[KEY_OCR_INTERVAL_MS] ?: SubtitleRoiSettings.DEFAULT_OCR_INTERVAL_MS)
            .coerceIn(
                SubtitleRoiSettings.MIN_OCR_INTERVAL_MS,
                SubtitleRoiSettings.MAX_OCR_INTERVAL_MS
            )

        SubtitleRoiSettings(
            roi = roi,
            isUserConfigured = configured,
            ocrIntervalMs = interval
        )
    }

    suspend fun saveRoi(roi: SubtitleRoi) {
        val safe = if (roi.isValid()) roi else roi.clamped()
        context.roiDataStore.edit { prefs ->
            prefs[KEY_LEFT] = safe.left
            prefs[KEY_TOP] = safe.top
            prefs[KEY_RIGHT] = safe.right
            prefs[KEY_BOTTOM] = safe.bottom
            if (safe.referenceAspectRatio != null && safe.referenceAspectRatio > 0f) {
                prefs[KEY_ASPECT_RATIO] = safe.referenceAspectRatio
            } else {
                prefs.remove(KEY_ASPECT_RATIO)
            }
            prefs[KEY_CONFIGURED] = true
        }
    }

    suspend fun resetRoi() {
        context.roiDataStore.edit { prefs ->
            prefs[KEY_LEFT] = SubtitleRoi.DEFAULT.left
            prefs[KEY_TOP] = SubtitleRoi.DEFAULT.top
            prefs[KEY_RIGHT] = SubtitleRoi.DEFAULT.right
            prefs[KEY_BOTTOM] = SubtitleRoi.DEFAULT.bottom
            prefs.remove(KEY_ASPECT_RATIO)
            prefs[KEY_CONFIGURED] = false
        }
    }

    suspend fun updateOcrIntervalMs(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(
            SubtitleRoiSettings.MIN_OCR_INTERVAL_MS,
            SubtitleRoiSettings.MAX_OCR_INTERVAL_MS
        )
        context.roiDataStore.edit { prefs ->
            prefs[KEY_OCR_INTERVAL_MS] = clamped
        }
    }

    companion object {
        private val KEY_LEFT = floatPreferencesKey("roi_left")
        private val KEY_TOP = floatPreferencesKey("roi_top")
        private val KEY_RIGHT = floatPreferencesKey("roi_right")
        private val KEY_BOTTOM = floatPreferencesKey("roi_bottom")
        private val KEY_ASPECT_RATIO = floatPreferencesKey("roi_aspect_ratio")
        private val KEY_CONFIGURED = booleanPreferencesKey("roi_configured")
        private val KEY_OCR_INTERVAL_MS = longPreferencesKey("ocr_interval_ms")
    }
}
