package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.auth.model.AuthPermission
import com.github.mihanizzm.ultistats.auth.security.*
import com.github.mihanizzm.ultistats.service.MatchService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals

class ObsInfographicsLeadersPolicyTest {
    @Test fun `leaders API and page require secure OBS ADMIN while mutation is denied`() {
        val policy = AuthSurfacePolicyImpl(mock(MatchService::class.java))
        for (path in listOf("/api/v1/obs/infographics/leaders/auto", "/api/v1/obs/infographics/leaders/00000000-0000-0000-0000-000000000001",
            "/obs-leaders.html", "/obs/leaders.mjs", "/obs/leaders.css")) {
            assertEquals(AuthRequirement.Permission(AuthPermission.OBS_ADMIN, true), policy.requirement(MockHttpServletRequest("GET", path)), path)
            assertEquals(AuthRequirement.Deny, policy.requirement(MockHttpServletRequest("POST", path)), path)
        }
    }
}
