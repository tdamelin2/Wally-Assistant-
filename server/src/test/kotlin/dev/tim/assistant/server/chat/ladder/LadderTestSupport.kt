package dev.tim.assistant.server.chat.ladder

import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Exam
import dev.tim.assistant.db.Health_day
import dev.tim.assistant.db.Journal
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.chat.ChatRig

/** The clock of these tests is 2026-11-11 12:00 Jerusalem time (a Wednesday); the week started on Sunday 2026-11-08. */
const val TODAY = "2026-11-11"

suspend fun ChatRig.task(id: String, title: String, due: String? = null, done: Boolean = false) =
    seed(toSyncRow(Task("task:$id", 1000, 0, 0, "SYNCED", title, due, if (done) 1 else 0, if (done) 1000 else null, null)))

suspend fun ChatRig.exam(id: String, title: String, date: String, time: String? = null) =
    seed(toSyncRow(Exam("exam:$id", 1000, 0, 0, "SYNCED", null, title, date, time)))

suspend fun ChatRig.meal(id: String, text: String, eatenAt: Long, date: String = TODAY) =
    seed(toSyncRow(Meal("meal:$id", eatenAt, 0, 0, "SYNCED", "MEAL", text, eatenAt, date, null, null, null, null, null)))

suspend fun ChatRig.spend(id: String, agorot: Long, category: String, note: String?, date: String = TODAY, updatedAt: Long = 1000) =
    seed(toSyncRow(Spending("spending:$id", updatedAt, 0, 0, "SYNCED", agorot, category, note, date)))

suspend fun ChatRig.checkin(date: String = TODAY) =
    seed(toSyncRow(Checkin("checkin:$date", 1000, 0, 0, "SYNCED", date, 4, 3, 4, 2, null)))

suspend fun ChatRig.journal(id: String, text: String, createdAt: Long) =
    seed(toSyncRow(Journal("journal:$id", createdAt, 0, 0, "SYNCED", text, createdAt)))

suspend fun ChatRig.event(id: String, title: String, start: Long, end: Long, allDay: Boolean = false) =
    seed(toSyncRow(Cal_event("cal:primary:$id", 1000, 0, 0, "SYNCED", "primary", id, title, start, end, if (allDay) 1 else 0, null)))

suspend fun ChatRig.mealKcal(id: String, text: String, eatenAt: Long, date: String, kcal: Long?, type: String = "MEAL") =
    seed(toSyncRow(Meal("meal:$id", eatenAt, 0, 0, "SYNCED", type, text, eatenAt, date, kcal, null, null, null, null)))

suspend fun ChatRig.checkinScores(date: String, mood: Long, energy: Long, sleep: Long, stress: Long) =
    seed(toSyncRow(Checkin("checkin:$date", 1000, 0, 0, "SYNCED", date, mood, energy, sleep, stress, null)))

suspend fun ChatRig.healthDay(date: String, sleepMinutes: Long?, steps: Long?, restingHr: Long?) =
    seed(toSyncRow(Health_day("health:$date", 1000, 0, 0, "SYNCED", date, sleepMinutes, steps, restingHr)))
