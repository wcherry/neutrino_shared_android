package com.neutrino.shared.core

import com.neutrino.shared.core.crypto.B64
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.crypto.PlaceEnvelope
import com.neutrino.shared.core.crypto.SecretStream
import com.neutrino.shared.core.crypto.Sodium
import com.neutrino.shared.core.keys.KeyBundleParser
import com.neutrino.shared.core.keys.Keyring
import com.neutrino.shared.core.keys.KeyringCodec
import com.neutrino.shared.core.keys.MobileKeyQr
import com.neutrino.shared.core.keys.Pairing
import com.neutrino.shared.core.keys.RecoveryKit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.system.exitProcess

/**
 * The Kotlin half of `scripts/crosscheck-web-crypto.sh`, which runs the web's own `e2e-crypto`
 * sources on real libsodium against what this emits, and has this open what the web produced.
 *
 *   emit   <out.json>                 Android-made artifacts for the web to open
 *   verify <android.json> <web.json>  open the web-made artifacts here
 */
object CrossCheck {
    const val USER = "user-123"
    const val PLAINTEXT = "Plan — v2 · ünïcødé · 東京"
    const val PIN = "246810"
    const val PLACE_NAME = "Café — Westminster"

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.getOrNull(0)) {
            "emit" -> File(args[1]).writeText(emit().toString())
            "verify" -> exitProcess(if (verify(File(args[1]).readText(), File(args[2]).readText())) 0 else 1)
            else -> error("usage: emit <out> | verify <android.json> <web.json>")
        }
    }

    private fun emit(): JsonObject {
        val retired = Sodium.boxKeyPair()
        val active = Sodium.boxKeyPair()
        val ring = Keyring(
            USER,
            listOf(
                Keyring.fromKeyPair(USER, retired.secretKey, 1).entries[0].retired("2026-01-01T00:00:00.000Z"),
                Keyring.fromKeyPair(USER, active.secretKey, 2).entries[0],
            ),
        )
        val dek = FileCrypto.generateDek()
        val large = ByteArray(200_003) { (it * 31).toByte() }
        val largeOut = ByteArrayOutputStream()
        SecretStream.encrypt(large.inputStream(), largeOut, dek)
        val session = Pairing.createSession()
        val inner = """{"public_key":"${B64.encode(active.publicKey)}","private_key":"${B64.encode(active.secretKey)}","key_version":"2"}"""
        return buildJsonObject {
            put("secretKey", B64.encode(active.secretKey))
            put("publicKey", B64.encode(active.publicKey))
            put("retiredSecretKey", B64.encode(retired.secretKey))
            put("dek", B64.encode(dek))
            put("sealedDek", FileCrypto.sealDek(dek, active.publicKey))
            put("plaintext", PLAINTEXT)
            put("encryptedFile", B64.encode(FileCrypto.encryptFile(PLAINTEXT.toByteArray(), dek)))
            put("largeFileSha256", FileCrypto.sha256Hex(large))
            put("encryptedLargeFile", B64.encode(largeOut.toByteArray()))
            put("encryptedMetadata", FileCrypto.encryptMetadata("Plan — v2.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", dek))
            put("keyringJson", KeyringCodec.encodeJson(ring))
            put("recoveryKit", RecoveryKit.export(ring))
            put("pairingOffer", Pairing.encode(session.offer))
            put("pairingSecret", B64.encode(session.secretForCrossCheck()))
            put("keyQr", MobileKeyQr.encrypt(inner, PIN))
            put("keyQrPin", PIN)
            put("place", PlaceEnvelope.seal(PlaceEnvelope.Payload(51.5007, -0.1246, PLACE_NAME, 150), active.publicKey, 2))
        }
    }

    private fun verify(androidJson: String, webJson: String): Boolean {
        val a = Json.parseToJsonElement(androidJson).jsonObject
        val w = Json.parseToJsonElement(webJson).jsonObject
        fun s(o: JsonObject, k: String) = o[k]!!.jsonPrimitive.content
        var failures = 0
        fun check(name: String, block: () -> Boolean) {
            val ok = runCatching(block).getOrElse { println("FAIL  $name  $it"); failures++; return }
            println("${if (ok) "PASS" else "FAIL"}  $name")
            if (!ok) failures++
        }
        val secret = B64.decode(s(a, "secretKey"))!!

        check("web crypto_box_seal opens here") {
            FileCrypto.openSealedDek(s(w, "sealedDek"), secret)!!.contentEquals(B64.decode(s(a, "dek"))!!)
        }
        check("web secretstream file decrypts here") {
            FileCrypto.decryptFile(B64.decode(s(w, "encryptedFile"))!!, B64.decode(s(a, "dek"))!!).decodeToString() == PLAINTEXT
        }
        check("web metadata decrypts here") {
            FileCrypto.decryptMetadata(s(w, "encryptedMetadata"), B64.decode(s(a, "dek"))!!).name == "Plan — v2.docx"
        }
        check("web keyring JSON decodes here") {
            val ring = KeyringCodec.decodeJson(s(w, "keyringJson"))
            ring.active!!.version == 2 && ring.entry(1)!!.secretKey.contentEquals(B64.decode(s(a, "retiredSecretKey"))!!)
        }
        check("web recovery kit imports here") {
            RecoveryKit.import(s(w, "recoveryKit"), USER).active!!.secretKey.contentEquals(secret)
        }
        check("web key QR opens here with its PIN") {
            val bundle = KeyBundleParser.parse(MobileKeyQr.decrypt(s(w, "keyQr"), s(w, "keyQrPin")))
            bundle.keyVersion == "2" && B64.decode(bundle.privateKey)!!.contentEquals(secret)
        }
        check("web pairing reply opens here, same confirmation code") {
            val offer = Json.decodeFromString(Pairing.Offer.serializer(), s(a, "pairingOffer"))
            val session = Pairing.sessionForCrossCheck(offer, B64.decode(s(a, "pairingSecret"))!!)
            val response = Pairing.parseResponse(s(w, "pairingResponse"))
            val ring = Pairing.accept(response, session, USER)
            ring.active!!.secretKey.contentEquals(secret) &&
                Pairing.confirmationCode(offer, response) == s(w, "pairingCode")
        }
        check("web saved place opens here") {
            val place = PlaceEnvelope.open(s(w, "place"), secret)
            PlaceEnvelope.keyVersion(s(w, "place")) == 2 && place.name == PLACE_NAME && place.radiusM == 150 && place.lat == 51.5007
        }
        if (failures > 0) println("$failures cross-check(s) failed") else println("All Android-side cross-checks passed.")
        return failures == 0
    }
}
