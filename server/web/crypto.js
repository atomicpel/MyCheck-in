// Сквозное шифрование «Учёт SIM» (см. docs/SECURITY.md). Только WebCrypto, без сторонних библиотек.
// Ключи живут только в памяти вкладки и не покидают браузер (кроме authKey — для входа).

const enc = new TextEncoder();
const dec = new TextDecoder();
const subtle = globalThis.crypto.subtle;

export const KDF_ITER = 600000;

export function b64(bytes) {
  let s = '';
  const b = new Uint8Array(bytes);
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
  return btoa(s);
}

export function unb64(str) {
  const s = atob(str);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

export function randomBytes(n) {
  return globalThis.crypto.getRandomValues(new Uint8Array(n));
}

export function newId() {
  return globalThis.crypto.randomUUID();
}

async function hkdf(master, info) {
  const k = await subtle.importKey('raw', master, 'HKDF', false, ['deriveBits']);
  const bits = await subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: enc.encode(info) }, k, 256);
  return new Uint8Array(bits);
}

/** Мастер-пароль → authKey (для сервера) и kek (только здесь). */
export async function deriveKeys(password, kdf) {
  if (kdf.alg !== 'PBKDF2-SHA256' || kdf.iter < KDF_ITER) throw new Error('Неподдерживаемые параметры ключа');
  const pw = await subtle.importKey('raw', enc.encode(password.normalize('NFC')), 'PBKDF2', false, ['deriveBits']);
  const master = new Uint8Array(await subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: unb64(kdf.salt), iterations: kdf.iter }, pw, 256));
  const authKey = await hkdf(master, 'simvault-auth-v1');
  const kekRaw = await hkdf(master, 'simvault-kek-v1');
  master.fill(0);
  const kek = await subtle.importKey('raw', kekRaw, 'AES-GCM', false, ['encrypt', 'decrypt']);
  kekRaw.fill(0);
  return { authKey: b64(authKey), kek };
}

async function seal(key, plain, aad) {
  const iv = randomBytes(12);
  const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv, additionalData: enc.encode(aad) }, key, plain));
  const out = new Uint8Array(12 + ct.length);
  out.set(iv);
  out.set(ct, 12);
  return out;
}

async function open(key, sealed, aad) {
  const b = sealed instanceof Uint8Array ? sealed : new Uint8Array(sealed);
  return new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv: b.subarray(0, 12), additionalData: enc.encode(aad) }, key, b.subarray(12)));
}

/** Новое хранилище: случайный vaultKey, завёрнутый ключом kek. */
export async function createVault(password) {
  const kdf = { alg: 'PBKDF2-SHA256', iter: KDF_ITER, salt: b64(randomBytes(16)) };
  const { authKey, kek } = await deriveKeys(password, kdf);
  const raw = randomBytes(32);
  const wrappedKey = b64(await seal(kek, raw, 'simvault-vaultkey-v1'));
  const vaultKey = await subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
  raw.fill(0);
  return { kdf, authKey, wrappedKey, vaultKey };
}

/** Развернуть vaultKey. [extractable] — только для смены пароля. */
export async function unwrapVaultKey(kek, wrappedKey, extractable = false) {
  const raw = await open(kek, unb64(wrappedKey), 'simvault-vaultkey-v1');
  const key = await subtle.importKey('raw', raw, 'AES-GCM', extractable, ['encrypt', 'decrypt']);
  if (!extractable) raw.fill(0);
  return extractable ? { key, raw } : key;
}

/** Смена пароля: тот же vaultKey под новым ключом. */
export async function rewrap(rawVaultKey, newPassword) {
  const kdf = { alg: 'PBKDF2-SHA256', iter: KDF_ITER, salt: b64(randomBytes(16)) };
  const { authKey, kek } = await deriveKeys(newPassword, kdf);
  const wrappedKey = b64(await seal(kek, rawVaultKey, 'simvault-vaultkey-v1'));
  return { kdf, authKey, wrappedKey };
}

export async function encryptRecord(vaultKey, id, obj) {
  return b64(await seal(vaultKey, enc.encode(JSON.stringify(obj)), 'rec:' + id));
}

export async function decryptRecord(vaultKey, id, data) {
  return JSON.parse(dec.decode(await open(vaultKey, unb64(data), 'rec:' + id)));
}

export async function encryptBlob(vaultKey, id, bytes) {
  return seal(vaultKey, bytes, 'blob:' + id);
}

export async function decryptBlob(vaultKey, id, bytes) {
  return open(vaultKey, new Uint8Array(bytes), 'blob:' + id);
}
