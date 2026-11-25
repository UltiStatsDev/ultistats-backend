package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.auth.model.AuthPermission
import com.github.mihanizzm.ultistats.auth.security.AuthRequirement
import com.github.mihanizzm.ultistats.auth.security.AuthSurfacePolicyImpl
import com.github.mihanizzm.ultistats.service.MatchService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals

class ObsInfographicsPresentationPolicyTest {
    @Test fun `only private secure mutation routes are mapped`() {
        val policy = AuthSurfacePolicyImpl(mock(MatchService::class.java))
        val path = "/api/v1/obs/infographics/presentations"
        val generation = "$path/00000000-0000-0000-0000-000000000001"
        val staff = AuthRequirement.Permission(AuthPermission.OBS_ADMIN, true)
        assertEquals(staff, policy.requirement(MockHttpServletRequest("POST", path)))
        assertEquals(staff, policy.requirement(MockHttpServletRequest("DELETE", generation)))
        assertEquals(AuthRequirement.Deny, policy.requirement(MockHttpServletRequest("GET", path)))
        assertEquals(AuthRequirement.Deny, policy.requirement(MockHttpServletRequest("PUT", generation)))
        assertEquals(AuthRequirement.Deny, policy.requirement(MockHttpServletRequest("POST", "/api/v1/obs/sources/infographics/auto")))
    }
}
