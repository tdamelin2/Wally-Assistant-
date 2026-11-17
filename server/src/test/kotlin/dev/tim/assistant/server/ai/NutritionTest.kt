package dev.tim.assistant.server.ai

import dev.tim.assistant.db.Meal
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.server.obj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val GOOD = """{"kcal":450,"proteinG":20,"carbsG":50,"fatG":15}"""

private fun meal(
    id: String,
    updatedAt: Long = 100,
    text: String = "pasta",
    eatenAt: Long = 1_700_000_000_000L,
    type: String = "MEAL",
    kcal: Long? = null,
    deleted: Boolean = false,
) = SyncRow(
    "meal", id, updatedAt, deleted,
    obj(
        "type" to type, "text" to text, "eatenAt" to eatenAt, "localDate" to "2023-11-15",
        "kcal" to kcal, "proteinG" to null, "carbsG" to null, "fatG" to null, "estimateModel" to null,
    ),
)

private fun estimateRig(yieldWhen: () -> Boolean = { false }) = AiRig(yieldWhen = yieldWhen).also { it.fake.reply = { 200 to chatBody(GOOD) } }

class NutritionTest {
    private fun AiRig.meal(id: String): Meal = assertNotNull(sdb.db.mealQueries.selectById(id).executeAsOneOrNull())

    private fun AiRig.useEstimate(json: String = GOOD) {
        fake.reply = { 200 to chatBody(json) }
    }

    @Test
    fun aValidReplyFillsTheFourFieldsAndTheModel() = runBlocking {
        val rig = estimateRig()
        try {
            rig.store.push(listOf(meal("m1", text = "two eggs and toast", type = "SNACK")))
            assertEquals(1, rig.nutrition.run())
            val m = rig.meal("m1")
            assertEquals(450L, m.kcal)
            assertEquals(20L, m.proteinG)
            assertEquals(50L, m.carbsG)
            assertEquals(15L, m.fatG)
            assertEquals("test-model", m.estimateModel)
            val body = rig.fake.requestJson()
            assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
            assertEquals("false", body["think"]!!.jsonPrimitive.content)
            assertEquals("false", body["stream"]!!.jsonPrimitive.content)
            assertEquals("8192", body["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.content)
            val format = body["format"]!!.jsonObject
            assertEquals("object", format["type"]!!.jsonPrimitive.content)
            assertEquals(setOf("kcal", "proteinG", "carbsG", "fatG"), format["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
            val messages = body["messages"]!!.jsonArray
            assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals("SNACK: two eggs and toast", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
            assertTrue("never instructions" in messages[0].jsonObject["content"]!!.jsonPrimitive.content)
            assertFalse(rig.lock.isLocked)
        } finally {
            rig.close()
        }
    }

    @Test
    fun badRepliesLeaveTheMealUnestimated() = runBlocking {
        val replies = listOf(
            "not json",
            """{"kcal":-5,"proteinG":1,"carbsG":1,"fatG":1}""",
            """{"kcal":6000,"proteinG":1,"carbsG":1,"fatG":1}""",
            """{"kcal":"450","proteinG":1,"carbsG":1,"fatG":1}""",
            """{"kcal":450,"proteinG":1,"carbsG":1}""",
            """{"kcal":450,"proteinG":1,"carbsG":-1,"fatG":1}""",
            """{"kcal":450.5,"proteinG":1,"carbsG":1,"fatG":1}""",
            """[450]""",
        )
        for (reply in replies) {
            val rig = estimateRig()
            try {
                rig.store.push(listOf(meal("m1")))
                rig.useEstimate(reply)
                val seqBefore = rig.seqCounter()
                assertEquals(0, rig.nutrition.run(), reply)
                assertNull(rig.meal("m1").kcal, reply)
                assertNull(rig.meal("m1").estimateModel, reply)
                assertEquals(seqBefore, rig.seqCounter(), reply)
            } finally {
                rig.close()
            }
        }
    }

    @Test
    fun kcalOf5000IsAccepted() = runBlocking {
        val rig = estimateRig()
        try {
            rig.store.push(listOf(meal("m1")))
            rig.useEstimate("""{"kcal":5000,"proteinG":0,"carbsG":0,"fatG":0}""")
            assertEquals(1, rig.nutrition.run())
            assertEquals(5000L, rig.meal("m1").kcal)
        } finally {
            rig.close()
        }
    }

    @Test
    fun parseEstimateRules() {
        assertEquals(Estimate(450, 20, 50, 15), parseEstimate(GOOD))
        assertEquals(Estimate(450, 20, 50, 15), parseEstimate("  $GOOD \n"))
        assertNull(parseEstimate("```json\n$GOOD\n```"))
        assertNull(parseEstimate(""))
        assertNull(parseEstimate("""{"kcal":null,"proteinG":1,"carbsG":1,"fatG":1}"""))
        assertEquals(Estimate(1, 2, 3, 4), parseEstimate("""{"kcal":1,"proteinG":2,"carbsG":3,"fatG":4,"extra":"x"}"""))
    }

    @Test
    fun aMealEditedDuringTheCallIsNotOverwritten() = runBlocking {
        val rig = estimateRig()
        try {
            rig.store.push(listOf(meal("m1", updatedAt = 100, text = "old text")))
            rig.useEstimate()
            var seqAfterEdit: String? = null
            rig.fake.beforeReply = {
                rig.store.push(listOf(meal("m1", updatedAt = 200, text = "new text")))
                seqAfterEdit = rig.seqCounter()
            }
            assertEquals(0, rig.nutrition.run())
            val m = rig.meal("m1")
            assertEquals("new text", m.text)
            assertEquals(200L, m.updatedAt)
            assertNull(m.kcal)
            assertEquals(seqAfterEdit, rig.seqCounter(), "a stale skip must not bump seqCounter")
        } finally {
            rig.close()
        }
    }

    @Test
    fun aWrittenEstimateKeepsUpdatedAtAndGetsAHigherServerSeq() = runBlocking {
        val rig = estimateRig()
        try {
            rig.store.push(listOf(meal("m1", updatedAt = 100)))
            val before = rig.meal("m1")
            assertEquals(1, rig.nutrition.run())
            val after = rig.meal("m1")
            assertEquals(before.updatedAt, after.updatedAt)
            assertEquals(before.text, after.text)
            assertEquals(before.type, after.type)
            assertEquals(before.eatenAt, after.eatenAt)
            assertTrue(after.serverSeq > before.serverSeq)
            assertEquals(after.serverSeq.toString(), rig.seqCounter())
            val pulled = rig.store.pull(before.serverSeq).rows.single { it.id == "m1" }
            assertEquals(450L, pulled.data["kcal"]!!.jsonPrimitive.content.toLong())
        } finally {
            rig.close()
        }
    }

    @Test
    fun aLaterEditWithAHigherUpdatedAtBeatsTheEstimateAndIsEstimatedAgain() = runBlocking {
        val rig = estimateRig()
        try {
            rig.store.push(listOf(meal("m1", updatedAt = 100)))
            assertEquals(1, rig.nutrition.run())
            assertEquals(450L, rig.meal("m1").kcal)
            val response = rig.store.push(listOf(meal("m1", updatedAt = 300, text = "bigger pasta")))
            assertEquals(listOf("m1"), response.accepted)
            val edited = rig.meal("m1")
            assertEquals(300L, edited.updatedAt)
            assertEquals("bigger pasta", edited.text)
            assertNull(edited.kcal)
            rig.useEstimate("""{"kcal":700,"proteinG":30,"carbsG":80,"fatG":25}""")
            assertEquals(1, rig.nutrition.run())
            assertEquals(700L, rig.meal("m1").kcal)
            assertEquals(300L, rig.meal("m1").updatedAt)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aRepushWithTheSameUpdatedAtIsAcceptedWithoutAWriteAndTheEstimateStays() = runBlocking {
        // P7-Q-CLA-01 option A: the strict sync rule stays; the device pulls the estimate
        val rig = estimateRig()
        try {
            rig.store.push(listOf(meal("m1", updatedAt = 100)))
            assertEquals(1, rig.nutrition.run())
            val estimated = rig.meal("m1")
            val seq = rig.seqCounter()
            val response = rig.store.push(listOf(meal("m1", updatedAt = 100)))
            assertEquals(listOf("m1"), response.accepted)
            assertTrue(response.rejected.isEmpty())
            val after = rig.meal("m1")
            assertEquals(450L, after.kcal)
            assertEquals(estimated.serverSeq, after.serverSeq)
            assertEquals(seq, rig.seqCounter())
            assertEquals(0, rig.nutrition.run())
            assertEquals(1, rig.fake.bodies.size)
            val pulled = rig.store.pull(0).rows.single { it.id == "m1" }
            assertEquals(450L, pulled.data["kcal"]!!.jsonPrimitive.content.toLong())
        } finally {
            rig.close()
        }
    }

    @Test
    fun sixthMealIsEstimatedWhenFiveOlderOnesFailedThreeTimes() = runBlocking {
        val rig = estimateRig()
        try {
            val base = 1_700_000_000_000L
            rig.store.push((1..5).map { meal("bad$it", text = "bad $it", eatenAt = base + it) } + meal("good", text = "good one", eatenAt = base + 10))
            rig.fake.reply = { body -> if ("bad " in body) 500 to "{}" else 200 to chatBody(GOOD) }
            repeat(NUTRITION_MAX_FAILURES) { assertEquals(0, rig.nutrition.run()) }
            assertEquals(5 * NUTRITION_MAX_FAILURES, rig.fake.bodies.size)
            assertNull(rig.meal("good").kcal)
            assertEquals(1, rig.nutrition.run())
            assertEquals(450L, rig.meal("good").kcal)
            assertEquals(5 * NUTRITION_MAX_FAILURES + 1, rig.fake.bodies.size)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aMealThatFailedThreeTimesIsSkipped() = runBlocking {
        val rig = estimateRig()
        try {
            rig.store.push(listOf(meal("m1")))
            rig.fake.reply = { 200 to chatBody("nope") }
            repeat(NUTRITION_MAX_FAILURES) { rig.nutrition.run() }
            assertEquals(NUTRITION_MAX_FAILURES, rig.fake.bodies.size)
            rig.nutrition.run()
            assertEquals(NUTRITION_MAX_FAILURES, rig.fake.bodies.size)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aSuccessfulMealAfterAFailureStillWorksForAnotherMeal() = runBlocking {
        val rig = estimateRig()
        try {
            val base = 1_700_000_000_000L
            rig.store.push(listOf(meal("a", text = "bad", eatenAt = base), meal("b", text = "fine", eatenAt = base + 1)))
            rig.fake.reply = { body -> if ("MEAL: bad" in body) 500 to "{}" else 200 to chatBody(GOOD) }
            assertEquals(1, rig.nutrition.run())
            assertNull(rig.meal("a").kcal)
            assertEquals(450L, rig.meal("b").kcal)
        } finally {
            rig.close()
        }
    }

    @Test
    fun nothingRunsWhileTheGenerationLockIsHeld() = runBlocking {
        val rig = estimateRig()
        try {
            rig.store.push(listOf(meal("m1")))
            rig.lock.lock()
            assertEquals(0, rig.nutrition.run())
            assertEquals(0, rig.fake.bodies.size)
            rig.lock.unlock()
            assertEquals(1, rig.nutrition.run())
        } finally {
            rig.close()
        }
    }

    @Test
    fun atMostFiveMealsPerRun() = runBlocking {
        val rig = estimateRig()
        try {
            val base = 1_700_000_000_000L
            rig.store.push((1..8).map { meal("m$it", text = "food $it", eatenAt = base + it) })
            assertEquals(NUTRITION_PER_RUN, rig.nutrition.run())
            assertEquals(5, rig.fake.bodies.size)
            assertNull(rig.meal("m6").kcal)
            assertEquals(3, rig.nutrition.run())
            assertEquals(8, rig.fake.bodies.size)
        } finally {
            rig.close()
        }
    }

    @Test
    fun deletedAndAlreadyEstimatedMealsAreNotSent() = runBlocking {
        val rig = estimateRig()
        try {
            rig.store.push(listOf(meal("gone", deleted = true), meal("has", kcal = 300)))
            assertEquals(0, rig.nutrition.run())
            assertEquals(0, rig.fake.bodies.size)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aMealDeletedDuringTheCallIsNotEstimated() = runBlocking {
        val rig = estimateRig()
        try {
            rig.store.push(listOf(meal("m1", updatedAt = 100)))
            rig.fake.beforeReply = { rig.store.push(listOf(meal("m1", updatedAt = 200, deleted = true))) }
            assertEquals(0, rig.nutrition.run())
            assertNull(rig.meal("m1").kcal)
            assertEquals(1L, rig.meal("m1").deleted)
        } finally {
            rig.close()
        }
    }

    @Test
    fun writeEstimateRefusesStaleMealsWithoutTouchingTheCounter() = runBlocking {
        val rig = estimateRig()
        try {
            rig.store.push(listOf(meal("m1", updatedAt = 100)))
            val seq = rig.seqCounter()
            val e = Estimate(1, 2, 3, 4)
            val write = { id: String, at: Long -> rig.sdb.db.transactionWithResult { rig.store.writeEstimate(id, at, e, "m") } }
            assertFalse(write("missing", 100))
            assertFalse(write("m1", 99))
            assertEquals(seq, rig.seqCounter())
            assertTrue(write("m1", 100))
            assertFalse(write("m1", 100), "kcal is already set")
        } finally {
            rig.close()
        }
    }

    @Test
    fun yieldingStopsBetweenMeals() = runBlocking {
        var yieldNow = false
        val rig = estimateRig(yieldWhen = { yieldNow })
        try {
            val base = 1_700_000_000_000L
            rig.store.push((1..3).map { meal("m$it", eatenAt = base + it) })
            rig.fake.beforeReply = { yieldNow = true }
            assertEquals(1, rig.nutrition.run())
            assertEquals(1, rig.fake.bodies.size)
            assertFalse(rig.lock.isLocked)
        } finally {
            rig.close()
        }
    }

    @Test
    fun anOllamaFailureDoesNotLeaveTheLockHeld() = runBlocking {
        val rig = estimateRig()
        try {
            rig.store.push(listOf(meal("m1")))
            rig.fake.reply = { 500 to "{}" }
            assertEquals(0, rig.nutrition.run())
            assertFalse(rig.lock.isLocked)
        } finally {
            rig.close()
        }
    }
}
