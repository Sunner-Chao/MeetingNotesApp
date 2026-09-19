package com.oa.automation.ui.screen.account

import org.junit.Assert.*
import org.junit.Test

class ChannelQuestionnaireTest {
    private fun answers(pains: List<String> = listOf(ChannelQuestionnaire.painPoints.first()), suggestion: String = "") =
        ChannelQuestionnaire.answers("  小智  ", "杭州", ChannelQuestionnaire.scenes.first(),
            ChannelQuestionnaire.frequencies.first(), "", pains, "", suggestion, "")

    @Test fun optionalFieldsCanBeSkippedWhileLegacyRequiredPurposeRemainsReadable() {
        val result = requireNotNull(answers())
        assertEquals("小智", result["name"])
        assertTrue(result.getValue("purpose").contains(ChannelQuestionnaire.scenes.first()))
        assertTrue(result.getValue("purpose").contains(ChannelQuestionnaire.painPoints.first()))
        assertFalse(result.containsKey("contact"))
        assertFalse(result.containsKey("payment_preference"))
    }

    @Test fun cannotSubmitEmptyUnknownOrTooManyPainPoints() {
        assertNull(answers(emptyList()))
        assertNull(answers(listOf("unknown")))
        assertNull(answers(ChannelQuestionnaire.painPoints.take(4)))
        assertNotNull(answers(ChannelQuestionnaire.painPoints.take(3)))
    }

    @Test fun missingRequiredAnswersBlockSubmissionAndFreeTextFitsServerLimit() {
        assertNull(ChannelQuestionnaire.answers("", "杭州", "", "", "", emptyList(), "", "", ""))
        assertEquals(300, requireNotNull(answers(suggestion = "建议".repeat(500))).getValue("suggestion").length)
        assertTrue(requireNotNull(answers()).values.all { it.length <= 500 })
    }
}
