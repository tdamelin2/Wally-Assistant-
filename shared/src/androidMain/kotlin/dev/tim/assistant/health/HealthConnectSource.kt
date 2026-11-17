package dev.tim.assistant.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/** Reads the last 7 days from Health Connect. Returns null unless every read finished completely. */
class HealthConnectSource(private val context: Context) : HealthSource {
    private val zone = ZoneId.of("Asia/Jerusalem")

    override suspend fun readLast7Days(): RawHealth? {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return null
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            if (!client.permissionController.getGrantedPermissions().containsAll(HEALTH_READ_PERMISSIONS)) return null

            // The window is captured once so every read sees the same 7 days.
            val now = Instant.now()
            val windowStart = now.atZone(zone).toLocalDate().minusDays(6).atStartOfDay(zone).toInstant()
            // Overnight sleep may start before the window; the mapper's end-date rule decides which day it counts for.
            val rawFilter = TimeRangeFilter.between(windowStart.minus(Duration.ofHours(24)), now)

            val sleep = readAll(client, SleepSessionRecord::class, rawFilter)
                .filter { it.endTime >= windowStart }
                .map { r ->
                    val awake = r.stages.filter {
                        it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE ||
                            it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED ||
                            it.stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
                    }.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                    RawSleep(r.metadata.id, r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.metadata.dataOrigin.packageName, awake)
                }
            val restingHr = readAll(client, RestingHeartRateRecord::class, TimeRangeFilter.between(windowStart, now))
                .map { RawRestingHr(it.metadata.id, it.time.toEpochMilli(), it.beatsPerMinute, it.metadata.dataOrigin.packageName) }
            val exercise = readAll(client, ExerciseSessionRecord::class, TimeRangeFilter.between(windowStart, now))
                .map {
                    RawExercise(
                        it.metadata.id, it.startTime.toEpochMilli(), it.endTime.toEpochMilli(),
                        exerciseTypeName(it.exerciseType), null, it.metadata.dataOrigin.packageName,
                    )
                }

            val steps = HashMap<String, Long>()
            val buckets = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(
                        LocalDateTime.ofInstant(windowStart, zone), LocalDateTime.ofInstant(now, zone),
                    ),
                    timeRangeSlicer = Period.ofDays(1),
                    dataOriginFilter = setOf(DataOrigin(GARMIN_PACKAGE)),
                ),
            )
            for (b in buckets) {
                val total = b.result[StepsRecord.COUNT_TOTAL] ?: continue
                steps[b.startTime.toLocalDate().toString()] = total
            }
            RawHealth(sleep, restingHr, exercise, steps)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun <T : Record> readAll(client: HealthConnectClient, type: KClass<T>, filter: TimeRangeFilter): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, filter, pageToken = token))
            out += response.records
            token = response.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    private fun exerciseTypeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Running"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Treadmill running"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Stationary biking"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Pool swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Open water swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength training"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Rowing machine"
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "Other workout"
        else -> "Other"
    }
}
