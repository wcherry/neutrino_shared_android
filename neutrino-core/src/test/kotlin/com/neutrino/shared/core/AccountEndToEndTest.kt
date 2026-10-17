package com.neutrino.shared.core

import com.neutrino.shared.core.api.ApiException
import com.neutrino.shared.core.api.AuthException
import com.neutrino.shared.core.crypto.B64
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.crypto.Sodium
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.InMemoryKeyringStore
import com.neutrino.shared.core.keys.KeyException
import com.neutrino.shared.core.keys.KeyManager
import com.neutrino.shared.core.keys.Keyring
import com.neutrino.shared.core.keys.KeyringCodec
import com.neutrino.shared.core.keys.KeyringException
import com.neutrino.shared.core.keys.Pairing
import com.neutrino.shared.core.keys.RecoveryKit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Sign-in and key provisioning against [FakeServer]: real HTTP, real crypto. Each app tests its own flows on top. */
class AccountEndToEndTest {

    private lateinit var fake: FakeServer
    private lateinit var tmp: File

    @Before
    fun setUp() {
        fake = FakeServer()
        tmp = Files.createTempDirectory("nd-test").toFile()
    }

    @After
    fun tearDown() {
        fake.shutdown()
        tmp.deleteRecursively()
    }

    private class Client(val fake: FakeServer, tmp: File) {
        val api = fake.client()
        val keys = InMemoryKeyringStore()
        val check = DeviceKeyCheck(api, keys)
        val manager = KeyManager(api, keys, check)
    }

    private fun signedIn(): Client = Client(fake, tmp).also { runBlocking { it.api.auth.login(fake.email, fake.password) } }

    @Test
    fun loginRunsTheThreeStepFlowWithTheAndroidClientId() = runBlocking {
        val c = Client(fake, tmp)
        c.api.auth.login(fake.email, fake.password)
        assertEquals(fake.userId, c.api.auth.userId)
        assertTrue(fake.requestLog.any { it.startsWith("GET /api/v1/oauth/authorize?client_id=neutrino-android") })
        try {
            Client(fake, tmp).api.auth.login(fake.email, "wrong")
            fail()
        } catch (_: AuthException.InvalidCredentials) {
        }
    }

    @Test
    fun twoFactorIsAskedForThenAccepted() = runBlocking {
        fake.requireTotp = "123456"
        val c = Client(fake, tmp)
        try {
            c.api.auth.login(fake.email, fake.password); fail()
        } catch (_: AuthException.TwoFactorRequired) {
        }
        try {
            c.api.auth.login(fake.email, fake.password, "000000"); fail()
        } catch (_: AuthException.InvalidTwoFactorCode) {
        }
        c.api.auth.login(fake.email, fake.password, " 123456 ")
        assertTrue(c.api.auth.isSignedIn)
    }

    @Test
    fun a401RefreshesOnceAndARejectedRefreshSignsOut() = runBlocking {
        val c = signedIn()
        c.manager.provision()
        fake.revokedAccessTokens += c.api.auth.currentAccessToken!!
        c.api.publishedKey(fake.userId) // refreshed and retried transparently
        assertTrue(c.api.auth.isSignedIn)

        var signedOut = false
        c.api.auth.onSignedOut = { signedOut = true }
        fake.revokedAccessTokens += c.api.auth.currentAccessToken!!
        fake.refreshTokenValid = false
        try {
            c.api.publishedKey(fake.userId); fail()
        } catch (_: ApiException.SessionExpired) {
        }
        assertTrue(signedOut)
        assertFalse(c.api.auth.isSignedIn)
    }

    @Test
    fun provisionPublishesAndReturnsAKitThatRestoresTheSameKey() = runBlocking {
        val c = signedIn()
        assertTrue(c.manager.canProvision())
        val kit = c.manager.provision()
        assertEquals(1, fake.publishedKeys[fake.userId]!!.single().version)
        assertFalse(c.manager.canProvision())
        try {
            Client(fake, tmp).also { it.api.auth.login(fake.email, fake.password) }.manager.provision(); fail()
        } catch (_: KeyException.AlreadyPublished) {
        }
        // A second device restores from the kit and gets the same key.
        val other = signedIn()
        other.manager.restoreRecoveryKit(kit)
        assertArrayEquals(c.manager.keyring()!!.active!!.secretKey, other.manager.keyring()!!.active!!.secretKey)
    }

    @Test
    fun recoveryKitIsCheckedAgainstTheAccountAndRenumbered() = runBlocking {
        val c = signedIn()
        val pair = Sodium.boxKeyPair()
        // The directory calls this key v3; a kit printed long ago calls it v1.
        fake.publishedKeys[fake.userId] = mutableListOf(FakeServer.Published(B64.encode(pair.publicKey), 3))
        val kit = RecoveryKit.export(Keyring.fromKeyPair(fake.userId, pair.secretKey, 1))
        c.manager.restoreRecoveryKit(kit)
        assertEquals(3, c.manager.keyring()!!.active!!.version)

        val wrong = RecoveryKit.export(Keyring.fromKeyPair(fake.userId, Sodium.boxKeyPair().secretKey, 1))
        try {
            signedIn().manager.restoreRecoveryKit(wrong); fail()
        } catch (_: KeyException.KitDoesNotMatchAccount) {
        }
    }

    @Test
    fun pairingAcceptsTheSendersKeyring() = runBlocking {
        val c = signedIn()
        val ring = Keyring.fromKeyPair(fake.userId, Sodium.boxKeyPair().secretKey, 2)
        val session = Pairing.createSession()
        // What the sender (web) does with QR-A:
        val reply = Pairing.Response(
            Pairing.RESPONSE_TYPE, 1,
            B64.encode(Sodium.boxSeal(KeyringCodec.encodeJson(ring).toByteArray(), B64.decode(session.offer.pk)!!)),
            session.offer.n,
        )
        val parsed = Pairing.parseResponse(kotlinx.serialization.json.Json.encodeToString(Pairing.Response.serializer(), reply))
        c.manager.acceptPairing(parsed, session)
        assertEquals(2, c.manager.keyring()!!.active!!.version)

        // A keyring for another account is refused.
        val stranger = Keyring.fromKeyPair("someone-else", Sodium.boxKeyPair().secretKey, 1)
        val s2 = Pairing.createSession()
        val r2 = Pairing.Response(Pairing.RESPONSE_TYPE, 1,
            B64.encode(Sodium.boxSeal(KeyringCodec.encodeJson(stranger).toByteArray(), B64.decode(s2.offer.pk)!!)), s2.offer.n)
        try {
            c.manager.acceptPairing(r2, s2); fail()
        } catch (_: KeyringException.WrongAccount) {
        }
    }

    @Test
    fun eachAppSignsInWithItsOwnClientId() = runBlocking {
        val api = fake.client(config = com.neutrino.shared.core.api.NeutrinoApps.calendar)
        api.auth.login(fake.email, fake.password)
        assertTrue(fake.requestLog.any { it.startsWith("GET /api/v1/oauth/authorize?client_id=neutrino-calendar-android") })
        // A client the server never registered is refused, opaquely, as on a real server.
        fake.registeredClients -= "neutrino-calendar-android"
        try {
            fake.client(config = com.neutrino.shared.core.api.NeutrinoApps.calendar).auth.login(fake.email, fake.password); fail()
        } catch (_: AuthException.TokenExchangeFailed) {
        }
    }
}
