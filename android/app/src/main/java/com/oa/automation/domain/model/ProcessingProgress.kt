package com.oa.automation.domain.model

data class ProcessingProgress(
    val percent: Int,
    val stage: String,
    val isIndeterminate: Boolean = false
) {
    init {
        require(percent in 0..100)
        require(stage.isNotBlank())
    }
}
