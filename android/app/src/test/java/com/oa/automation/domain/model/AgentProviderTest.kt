package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentProviderTest {
    @Test
    fun `agent providers use friendly names without changing request values`() {
        assertEquals("智能体小悟", AgentProvider.CODEX_CLI.displayName)
        assertEquals("智能体小智", AgentProvider.CLAUDE_CLI.displayName)
        assertEquals("codex-cli", AgentProvider.CODEX_CLI.requestValue)
        assertEquals("claude-cli", AgentProvider.CLAUDE_CLI.requestValue)
    }
}
