import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PLAN 18.7 step 3: the ladder with the sorter off over both held-out files. A write or card that differs from the line's
 * expected tool or args, a write or card for a pass_up or guard line, or a code answer with the wrong data value is a failure.
 * Never prints a message text: only the id, the expected intent and the kind of failure.
 */
class RouterHoldoutTest {
    @Test
    fun test_AC3_router_holdout_no_wrong_action_on_commands_and_guards() {
        val verdicts = runHoldout(listOf("holdout-commands.jsonl", "holdout-guards.jsonl")) { HoldoutRig(sorter = null) }
        val commands = verdicts.filter { it.line.id.startsWith("h") }
        val handled = commands.count { it.outcome == Outcome.HANDLED && it.line.expect != "pass_up" }
        println("handled by router: $handled/${commands.size}")
        val notHandled = commands.filter { it.outcome == Outcome.NOT_HANDLED }
        println("not handled by router (ids and intents only): " + notHandled.joinToString { "${it.line.id}:${it.line.expect}" })
        val wrong = verdicts.filter { it.outcome == Outcome.WRONG }.map { describe(it) }
        assertEquals(emptyList(), wrong, "wrong action")
        assertEquals(60, commands.size)
        assertEquals(18, verdicts.size - commands.size)
    }
}
