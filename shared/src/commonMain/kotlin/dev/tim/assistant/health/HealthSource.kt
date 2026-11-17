package dev.tim.assistant.health

/** The Android package of the Garmin Connect app; only Health Connect records written by it are kept. */
const val GARMIN_PACKAGE = "com.garmin.android.apps.connectmobile"

/** Reads the last 7 days from Health Connect; null when nothing could be read completely. */
interface HealthSource {
    suspend fun readLast7Days(): RawHealth?
}

/** Phone-only permission controls the Health screen uses; the desktop passes none. */
interface HealthUi {
    fun available(): Boolean
    suspend fun hasPermissions(): Boolean
    fun requestPermissions(onResult: (Boolean) -> Unit)
}

data class RawHealth(
    val sleep: List<RawSleep>,
    val restingHr: List<RawRestingHr>,
    val exercise: List<RawExercise>,
    /** Steps per Jerusalem day (YYYY-MM-DD); a day with no data is absent, a measured 0 is 0. */
    val dailySteps: Map<String, Long>,
)

data class RawSleep(
    val id: String,
    val startAt: Long,
    val endAt: Long,
    val origin: String,
    val awakeMinutes: Long = 0,
)

data class RawRestingHr(val id: String, val at: Long, val bpm: Long, val origin: String)

data class RawExercise(
    val id: String,
    val startAt: Long,
    val endAt: Long,
    val type: String,
    val distanceMeters: Long?,
    val origin: String,
)
