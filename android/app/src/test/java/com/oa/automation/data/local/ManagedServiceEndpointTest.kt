package com.oa.automation.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedServiceEndpointTest {
    @Test fun oldManagedRoutesFollowConfiguredReleaseDefaults() {
        for (path in listOf("/api", "/api/agent", "/stt-cloud")) {
            val current = "https://cloud.example$path"
            assertEquals(current, resolveManagedServiceEndpoint("https://lstwin.space$path/", current))
            assertEquals(current, resolveManagedServiceEndpoint("https://auth.synthapi.asia$path", current))
        }
    }
    @Test fun localModelAndCustomServersAreNotMigrated() {
        for (saved in listOf("https://lstwin.space/stt-local", "https://custom.example/api", "http://10.0.2.2:8888")) {
            assertEquals(saved, resolveManagedServiceEndpoint(saved, "https://cloud.example/api"))
        }
    }

    @Test fun managedIpv4RoutesFollowConfiguredReleaseDefaults() {
        assertEquals(
            "https://118.25.43.185/api",
            resolveManagedServiceEndpoint("https://118.25.43.185/api/", "https://118.25.43.185/api")
        )
    }
}
