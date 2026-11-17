package dev.tim.assistant.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord

/** The four Health Connect read permissions Wally asks for. */
val HEALTH_READ_PERMISSIONS: Set<String> = setOf(
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(RestingHeartRateRecord::class),
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
)
