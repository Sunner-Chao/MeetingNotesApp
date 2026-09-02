package com.oa.automation.domain.model

/** Preferences for the pre-recording template workflow explainer. */
data class TemplateWorkflowPreferences(
    val reducedMotion: Boolean = false,
    val seenTemplateNames: Set<String> = emptySet()
) {
    companion object {
        val DEFAULT = TemplateWorkflowPreferences()
    }
}
