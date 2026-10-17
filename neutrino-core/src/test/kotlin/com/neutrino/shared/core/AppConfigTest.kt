package com.neutrino.shared.core

import com.neutrino.shared.core.api.NeutrinoApps
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shipped app identities are a namespace (root CLAUDE.md, rules 3 and 4): two co-installed apps
 * sharing a prefix or Keystore alias would read each other's session, and two sharing a client id
 * would be indistinguishable to the server. Each value must also stay what the server seeds.
 */
class AppConfigTest {

    @Test
    fun noTwoAppsShareAPrefixAClientIdOrAKeystoreAlias() {
        val apps = NeutrinoApps.all
        assertEquals(apps.size, apps.map { it.prefix }.toSet().size)
        assertEquals(apps.size, apps.map { it.oauthClientId }.toSet().size)
        assertEquals(apps.size, apps.map { it.keystoreAlias }.toSet().size)
        assertEquals(apps.size, apps.map { it.slug }.toSet().size)
    }

    @Test
    fun theShippedValuesMatchTheServerRowsAndTheIosPrefixes() {
        assertEquals(
            listOf("nd" to "neutrino-android", "ncal" to "neutrino-calendar-android", "ndoc" to "neutrino-docs-android"),
            NeutrinoApps.all.map { it.prefix to it.oauthClientId },
        )
    }
}
