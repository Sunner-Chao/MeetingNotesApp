package com.oa.automation.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentGatewayEndpointResolverTest {
    @Test
    fun derivesGatewayFromCurrentAccountServiceWhenNoEndpointWasSaved() {
        assertEquals(
            "https://meeting.example/api/agent",
            resolveAgentGatewayEndpoint(
                savedEndpoint = null,
                accountEndpoint = "https://meeting.example/api/",
                defaultEndpoint = "http://localhost:8090/api/agent"
            )
        )
    }

    @Test
    fun replacesLegacyLoopbackGatewayAfterRemoteLogin() {
        assertEquals(
            "https://meeting.example/api/agent",
            resolveAgentGatewayEndpoint(
                savedEndpoint = "http://localhost:8090/api/agent",
                accountEndpoint = "https://meeting.example/api",
                defaultEndpoint = "http://localhost:8090/api/agent"
            )
        )
    }

    @Test
    fun preservesExplicitRemoteGateway() {
        assertEquals(
            "https://agent.example/custom",
            resolveAgentGatewayEndpoint(
                savedEndpoint = "https://agent.example/custom/",
                accountEndpoint = "https://meeting.example/api",
                defaultEndpoint = "https://default.example/api/agent"
            )
        )
    }

    @Test
    fun fallsBackToBuildConfigurationBeforeLogin() {
        assertEquals(
            "https://default.example/api/agent",
            resolveAgentGatewayEndpoint(
                savedEndpoint = "",
                accountEndpoint = null,
                defaultEndpoint = "https://default.example/api/agent/"
            )
        )
    }
}
