package com.neutrino.shared.core.keys

import com.neutrino.shared.core.crypto.B64
import com.neutrino.shared.core.crypto.Sodium
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeysTest {

    private fun sk(seed: Int) = ByteArray(32) { seed.toByte() }

    private fun twoVersions(): Keyring {
        val retired = Keyring.fromKeyPair("user-1", sk(1), 1).entries[0].retired("2026-01-01T00:00:00.000Z")
        val active = Keyring.fromKeyPair("user-1", sk(2), 2).entries[0]
        return Keyring("user-1", listOf(retired, active))
    }

    // ── Keyring ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun keyringJsonRoundTripsAndDerivesPublicKeys() {
        val ring = twoVersions()
        val decoded = KeyringCodec.decodeJson(KeyringCodec.encodeJson(ring))
        assertEquals(ring, decoded)
        assertEquals(2, decoded.active!!.version)
        assertArrayEquals(Sodium.publicKeyFromSecret(sk(1)), decoded.entry(1)!!.publicKey)
        assertEquals("versions 1–2 (current: 2)", decoded.describe())
    }

    @Test
    fun writesExplicitNullLikeTheWeb() {
        val json = KeyringCodec.encodeJson(Keyring.fromKeyPair("u", sk(3), 1))
        assertTrue(json, json.contains("\"retiredAt\":null"))
        assertTrue(json.contains("\"userId\":\"u\""))
        assertTrue(json.startsWith("{\"v\":1,"))
    }

    @Test
    fun acceptsTheSwiftShapeWithRetiredAtOmitted() {
        val json = """{"v":1,"userId":"u","entries":[{"version":4,"sk":"${B64.encode(sk(4))}","createdAt":"x"}]}"""
        assertEquals(4, KeyringCodec.decodeJson(json).active!!.version)
    }

    @Test
    fun rejectsDamagedKeyrings() {
        val good = B64.encode(sk(1))
        fun mk(entries: String) = """{"v":1,"userId":"u","entries":[$entries]}"""
        expect<KeyringException.UnrecognisedFormat> { KeyringCodec.decodeJson("nope") }
        expect<KeyringException.UnrecognisedFormat> { KeyringCodec.decodeJson("""{"v":2,"userId":"u","entries":[]}""") }
        expect<KeyringException.Empty> { KeyringCodec.decodeJson(mk("")) }
        expect<KeyringException.BadSecretKeyLength> { KeyringCodec.decodeJson(mk("""{"version":1,"sk":"AAAA","createdAt":"x","retiredAt":null}""")) }
        expect<KeyringException.DuplicateVersions> {
            KeyringCodec.decodeJson(mk("""{"version":1,"sk":"$good","createdAt":"x","retiredAt":"y"},{"version":1,"sk":"$good","createdAt":"x","retiredAt":null}"""))
        }
        expect<KeyringException.NotExactlyOneActive> { KeyringCodec.decodeJson(mk("""{"version":1,"sk":"$good","createdAt":"x","retiredAt":"y"}""")) }
    }

    @Test
    fun toStringNeverPrintsTheSecret() {
        val printed = Keyring.fromKeyPair("u", sk(9), 1).toString()
        assertFalse(printed.contains(B64.encode(sk(9))))
        assertTrue(printed.contains("<redacted>"))
    }

    @Test
    fun mergeUnionsVersionsAndRefusesConflicts() {
        val a = twoVersions()
        val b = Keyring.fromKeyPair("user-1", sk(3), 3)
        val merged = Keyring.merge(a, b)!!
        assertEquals(listOf(1, 2, 3), merged.entries.map { it.version })
        assertEquals(3, merged.active!!.version)
        assertEquals("2026-01-01T00:00:00.000Z", merged.entry(1)!!.retiredAt)
        assertNull(Keyring.merge(a, Keyring.fromKeyPair("user-1", sk(7), 2)))
        assertNull(Keyring.merge(a, Keyring.fromKeyPair("someone", sk(2), 2)))
    }

    // ── Recovery kit ───────────────────────────────────────────────────────────────────────────

    @Test
    fun recoveryKitRoundTripsEveryVersion() {
        val kit = RecoveryKit.export(twoVersions())
        assertTrue(RecoveryKit.looksLikeKit(kit))
        kit.lines().forEach { assertTrue(it.split('-').size <= 8) }
        val restored = RecoveryKit.import(kit, "user-1")
        assertArrayEquals(sk(1), restored.entry(1)!!.secretKey)
        assertFalse(restored.entry(1)!!.isActive)
        assertArrayEquals(sk(2), restored.active!!.secretKey)
    }

    @Test
    fun recoveryKitToleratesTranscriptionNoise() {
        val sloppy = RecoveryKit.export(twoVersions()).lowercase().replace('0', 'o').replace('1', 'l').replace('\n', ' ')
        assertArrayEquals(sk(2), RecoveryKit.import(sloppy, "u").active!!.secretKey)
    }

    @Test
    fun recoveryKitReportsWhatIsWrong() {
        val kit = RecoveryKit.normalize(RecoveryKit.export(twoVersions()))
        expect<RecoveryKit.KitException.NotAKit> { RecoveryKit.import("", "u") }
        expect<RecoveryKit.KitException.Incomplete> { RecoveryKit.import(kit.take(40), "u") }
        expect<RecoveryKit.KitException.UnexpectedCharacter> { RecoveryKit.import("ABC!", "u") }
        expect<RecoveryKit.KitException.UnsupportedVersion> { RecoveryKit.import(RecoveryKit.encodeBase32(byteArrayOf(0x4E, 2, 0)), "u") }
        expect<RecoveryKit.KitException.NotAKit> { RecoveryKit.import(RecoveryKit.encodeBase32(byteArrayOf(0x41, 1, 0)), "u") }
    }

    @Test
    fun base32MatchesTheSwiftAndRustEncoders() {
        assertEquals("9R0G0", RecoveryKit.encodeBase32(byteArrayOf(0x4E, 0x01, 0x00)))
        assertArrayEquals(byteArrayOf(0x4E, 0x01, 0x00), RecoveryKit.decodeBase32("9R0G0"))
    }

    // ── Pairing ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aRelayedOfferChangesTheCodeAndAStaleReplyIsRefused() {
        val real = Pairing.createSession()
        val relay = Pairing.createSession()
        val ring = Keyring.fromKeyPair("user", sk(3), 1)
        val reply = Pairing.Response(Pairing.RESPONSE_TYPE, 1,
            B64.encode(Sodium.boxSeal(KeyringCodec.encodeJson(ring).toByteArray(), B64.decode(relay.offer.pk)!!)), relay.offer.n)
        assertNotEquals(Pairing.confirmationCode(real.offer, reply), Pairing.confirmationCode(relay.offer, reply))
        val code = Pairing.confirmationCode(real.offer, reply)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
        expect<Pairing.PairingException.DifferentAttempt> { Pairing.accept(reply, real, "user") }
        assertEquals(ring, Pairing.accept(reply, relay, "user"))
    }

    @Test
    fun theOfferIsTheWebShape() {
        val s = Pairing.createSession()
        val json = Pairing.encode(s.offer)
        assertTrue(json, json.startsWith("{\"t\":\"neutrino-pair-offer\",\"v\":1,\"pk\":"))
        assertEquals(32, B64.decode(s.offer.pk)!!.size)
        assertEquals(16, B64.decode(s.offer.n)!!.size)
        expect<Pairing.PairingException.NotAPairingCode> { Pairing.parseResponse("hello") }
        expect<Pairing.PairingException.NotAPairingCode> { Pairing.parseResponse(json) }
    }

    // ── Key bundle and the PIN QR ──────────────────────────────────────────────────────────────

    @Test
    fun keyBundleValidatesThePair() {
        val pk = B64.encode(Sodium.publicKeyFromSecret(sk(4)))
        val std = java.util.Base64.getEncoder().encodeToString(sk(4))
        val bundle = KeyBundleParser.parse("""{"public_key":"$pk","private_key":"$std","key_version":"3"}""")
        assertEquals(3, bundle.toKeyring("u").active!!.version)
        assertEquals(1, KeyBundleParser.parse("""{"pk":"$pk","sk":"$std"}""").toKeyring("u").active!!.version)
        expect<KeyImportException.InvalidJson> { KeyBundleParser.parse("[") }
        expect<KeyImportException.MissingFields> { KeyBundleParser.parse("""{"public_key":"a"}""") }
        expect<KeyImportException.KeyPairMismatch> {
            KeyBundleParser.parse("""{"public_key":"${B64.encode(ByteArray(32))}","private_key":"$std","key_version":"1"}""")
        }
        assertFalse(bundle.toString().contains(std))
    }

    @Test
    fun pinQrRoundTripsAndRejectsAWrongPin() {
        val pk = B64.encode(Sodium.publicKeyFromSecret(sk(5)))
        val inner = """{"public_key":"$pk","private_key":"${B64.encode(sk(5))}","key_version":"2"}"""
        val envelope = MobileKeyQr.encrypt(inner, "482913", iterations = 1000)
        assertTrue(MobileKeyQr.looksLikeEnvelope(envelope))
        assertEquals(inner, MobileKeyQr.decrypt(envelope, "482913"))
        expect<MobileKeyQr.QrException.WrongPin> { MobileKeyQr.decrypt(envelope, "000000") }
        expect<MobileKeyQr.QrException.UnsupportedAlgorithm> { MobileKeyQr.decrypt(envelope.replace("pbkdf2-sha256+xsalsa20", "rot13"), "1") }
    }

    @Test
    fun pbkdf2MatchesRfc7914Vector() {
        // RFC 7914 §11: PBKDF2-HMAC-SHA256("passwd", "salt", 1, 64) — first 32 bytes.
        val out = Sodium.pbkdf2Sha256("passwd", "salt".toByteArray(), 1, 32)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
            out.joinToString("") { "%02x".format(it) })
    }

    // ── Archived keys ──────────────────────────────────────────────────────────────────────────

    @Test
    fun archivedKeysOpenWithTheActiveKeyAndRefuseTampering() {
        val active = Keyring.fromKeyPair("u", sk(2), 2)
        val v1 = sk(1)
        val good = ArchivedKeys.ArchivedKey(1, B64.encode(Sodium.boxSeal(v1, active.active!!.publicKey)), B64.encode(Sodium.publicKeyFromSecret(v1)))
        val lying = good.copy(publicKey = B64.encode(Sodium.publicKeyFromSecret(sk(9))))
        val (outcome, entries) = ArchivedKeys.plan(ArchivedKeys.KeyFile("u", listOf(good), "c", "u"), active)
        assertEquals(1, outcome.recovered)
        assertArrayEquals(v1, entries.single().secretKey)
        assertFalse(entries.single().isActive)
        assertEquals(1, ArchivedKeys.plan(ArchivedKeys.KeyFile("u", listOf(lying), "c", "u"), active).first.unopenable)
        // Our "active" version appearing in a file of retired keys means we are stale.
        val stale = ArchivedKeys.plan(ArchivedKeys.KeyFile("u", listOf(good.copy(keyVersion = 2)), "c", "u"), active).first
        assertTrue(stale.activeIsStale)
        val merged = ArchivedKeys.apply(active, entries)
        assertEquals(listOf(1, 2), merged.entries.map { it.version })
        KeyringCodec.decodeJson(KeyringCodec.encodeJson(merged))
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
    }
}
