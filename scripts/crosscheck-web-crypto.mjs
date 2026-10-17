// The web half of scripts/crosscheck-web-crypto.sh: opens what the Android crypto produced with
// the web's own e2e-crypto sources and real libsodium, then produces web-made artifacts for the
// Android side to open. Run through the shell script, which stages the sources and dependencies.
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import sodium from 'libsodium-wrappers';
import * as crypto from './src/crypto.ts';
import * as keyring from './src/keyring.ts';
import * as recoveryKit from './src/recoveryKit.ts';
import * as pairing from './src/pairing.ts';
import * as mobileKeyQr from './src/mobileKeyQr.ts';
import * as place from './src/place.ts';
import { createRequire } from 'node:module';

await sodium.ready;
await crypto.initSodium();
// place.ts imports './crypto' without an extension, which tsx loads as a CommonJS module of its
// own, with its own libsodium handle; that copy has to be initialised too.
await createRequire(import.meta.url)('./src/crypto').initSodium();
const a = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const URL = sodium.base64_variants.URLSAFE_NO_PADDING;
const u = (s) => sodium.from_base64(s, URL);
const b = (x) => sodium.to_base64(x, URL);
let failures = 0;
const check = async (name, fn) => {
  try {
    const ok = await fn();
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}`);
    if (!ok) failures++;
  } catch (e) {
    console.log(`FAIL  ${name}  ${e}`);
    failures++;
  }
};

await check('public key derivation', () => b(sodium.crypto_scalarmult_base(u(a.secretKey))) === a.publicKey);
await check('Android crypto_box_seal opens on the web', () =>
  b(crypto.decryptFileKey(a.sealedDek, u(a.publicKey), u(a.secretKey))) === a.dek);
await check('Android secretstream file decrypts on the web', () =>
  new TextDecoder().decode(crypto.decryptFile(u(a.encryptedFile), u(a.dek))) === a.plaintext);
await check('Android streamed 200 KB file decrypts on the web', () =>
  createHash('sha256').update(crypto.decryptFile(u(a.encryptedLargeFile), u(a.dek))).digest('hex') === a.largeFileSha256);
await check('Android metadata decrypts on the web', () => {
  const m = crypto.decryptMetadata(a.encryptedMetadata, u(a.dek));
  return m.name === 'Plan — v2.docx' && String(m.mimeType).startsWith('application/vnd.openxml');
});
await check('Android keyring JSON deserialises on the web', () => {
  const ring = keyring.deserializeKeyring(JSON.parse(a.keyringJson));
  return keyring.activeEntry(ring).version === 2 && ring.entries.length === 2 && ring.userId === 'user-123';
});
await check('Android recovery kit imports on the web, and exports identically', () => {
  const ring = recoveryKit.importRecoveryKit(a.recoveryKit, 'user-123');
  const e1 = keyring.entryForVersion(ring, 1);
  const e2 = keyring.entryForVersion(ring, 2);
  const webKit = recoveryKit.exportRecoveryKit(keyring.deserializeKeyring(JSON.parse(a.keyringJson)));
  return e1.retiredAt !== null && b(e1.secretKey) === a.retiredSecretKey &&
    e2.retiredAt === null && b(e2.secretKey) === a.secretKey && webKit === a.recoveryKit;
});
await check('Android key QR opens on the web with its PIN', async () => {
  const inner = JSON.parse(await mobileKeyQr.openKeyQr(a.keyQr, a.keyQrPin));
  return inner.private_key === a.secretKey && inner.key_version === '2';
});

await check('Android saved place opens on the web', () => {
  const p = place.openPlace(a.place, u(a.publicKey), u(a.secretKey));
  return place.placeKeyVersion(a.place) === 2 && p.name === 'Café — Westminster' && p.radiusM === 150 && p.lat === 51.5007;
});

// Web-made artifacts for the Android side.
const ring = keyring.deserializeKeyring(JSON.parse(a.keyringJson));
const offer = pairing.parsePairingOffer(a.pairingOffer);
const response = pairing.respondToPairingOffer(ring, offer);
const qr = await mobileKeyQr.exportKeyQr(ring);
const web = {
  sealedDek: crypto.encryptFileKey(u(a.dek), u(a.publicKey)),
  encryptedFile: b(crypto.encryptFile(new TextEncoder().encode(a.plaintext), u(a.dek))),
  encryptedMetadata: crypto.encryptMetadata({ name: 'Plan — v2.docx', mimeType: 'application/pdf' }, u(a.dek)),
  keyringJson: JSON.stringify(keyring.serializeKeyring(ring)),
  recoveryKit: recoveryKit.exportRecoveryKit(ring),
  keyQr: qr.payload,
  keyQrPin: qr.pin,
  pairingResponse: pairing.encodePairingPayload(response),
  pairingCode: pairing.confirmationCode(offer, response),
  place: place.sealPlace({ name: 'Café — Westminster', lat: 51.5007, lng: -0.1246, radiusM: 150 }, u(a.publicKey), 2),
};
writeFileSync(process.argv[3], JSON.stringify(web));

if (failures) {
  console.error(`${failures} web-side cross-check(s) failed`);
  process.exit(1);
}
console.log('All web-side cross-checks passed: the web opens what the Android client seals.');
