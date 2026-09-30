package com.example.p2pchat

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Gives every install a stable RSA identity keypair (generated once on first run, then reused
 * forever), used to end-to-end encrypt private messages.
 *
 * Private messages are hybrid-encrypted: a fresh random AES-256 key encrypts the actual text
 * (fast, no message-size limit), and that AES key is wrapped with the recipient's RSA public
 * key (so only their matching private key can unwrap it). This lets messages keep gossiping in
 * plaintext-looking-but-opaque form through relay devices that are neither the sender nor the
 * recipient - they can store and forward the blob for the 24h TTL without ever being able to
 * read it.
 *
 * v3: earlier versions kept the private key in Android's hardware-backed Keystore. That sounds
 * like the more secure choice, but Keystore's RSA/OAEP support turned out to be a source of
 * repeated, hard-to-diagnose failures in practice - a missing block-mode authorization
 * (`setBlockModes`) silently broke every decrypt in one build, and even with that fixed there's
 * a second, well-known class of Keystore/OAEP bug: the *encrypt* side (a peer's public key,
 * reconstructed with the plain default JCE provider) and the *decrypt* side (our Keystore-backed
 * private key, handled by the separate "AndroidKeyStore" provider) don't always agree on the
 * OAEP mask-generation-function (MGF1) digest unless it's spelled out explicitly - and that
 * disagreement fails silently too, as a decrypt exception swallowed into "couldn't decrypt".
 *
 * The fix here is to stop relying on Keystore for this key entirely: the RSA keypair is now a
 * completely ordinary [java.security.KeyPair], generated and used through the same single JCE
 * provider on every device for both encryption and decryption, with the OAEP digest and MGF1
 * digest both spelled out explicitly via [OAEPParameterSpec] so there is no ambiguity for any
 * provider to get wrong. The private key is stored (base64-encoded PKCS8) in this app's private
 * SharedPreferences rather than in hardware-backed storage. That's a real, deliberate trade-off
 * - a compromised/rooted device could extract it - but for a hobby mesh-chat app that's a far
 * better bargain than a "secure" key that silently fails to decrypt anything at all. If stronger
 * at-rest protection is wanted later, `EncryptedSharedPreferences` (androidx.security.crypto)
 * is a drop-in way to raise the bar again without reintroducing Keystore's own RSA quirks.
 */
class CryptoManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "p2pchat_identity"
        private const val PREF_PRIVATE_KEY = "private_key_pkcs8_b64"
        private const val PREF_PUBLIC_KEY = "public_key_x509_b64"

        private const val RSA_KEY_SIZE = 2048
        private const val RSA_ALGORITHM = "RSA"
        private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPPadding"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        // Spelled out explicitly (rather than relying on a transformation-string shorthand like
        // "OAEPWithSHA-256AndMGF1Padding") so the OAEP main digest, the MGF1 digest, and the
        // label source are all pinned to the exact same values on every device and provider -
        // see the class doc above for why that ambiguity is worth eliminating outright.
        private val OAEP_PARAMS = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
        )
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyFactory = KeyFactory.getInstance(RSA_ALGORITHM)

    private val myPrivateKey: PrivateKey
    private val myPublicKeyBase64: String

    init {
        val storedPrivate = prefs.getString(PREF_PRIVATE_KEY, null)
        val storedPublic = prefs.getString(PREF_PUBLIC_KEY, null)

        if (storedPrivate != null && storedPublic != null) {
            myPrivateKey = keyFactory.generatePrivate(
                PKCS8EncodedKeySpec(Base64.decode(storedPrivate, Base64.NO_WRAP))
            )
            myPublicKeyBase64 = storedPublic
        } else {
            val pair = KeyPairGenerator.getInstance(RSA_ALGORITHM).apply { initialize(RSA_KEY_SIZE) }.genKeyPair()
            myPrivateKey = pair.private
            myPublicKeyBase64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
            prefs.edit()
                .putString(PREF_PRIVATE_KEY, Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
                .putString(PREF_PUBLIC_KEY, myPublicKeyBase64)
                .apply()
        }
    }

    /** Our public key, safe to hand out to any peer, encoded for JSON transport. */
    fun getMyPublicKeyBase64(): String = myPublicKeyBase64

    /** Encrypts [plainText] so only the holder of the private key matching [peerPublicKeyBase64] can read it. */
    fun encryptForPeer(peerPublicKeyBase64: String, plainText: String): String {
        val peerPublicKey = decodePublicKey(peerPublicKeyBase64)

        val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val aesCipher = Cipher.getInstance(AES_TRANSFORMATION)
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey)
        val iv = aesCipher.iv
        val cipherBytes = aesCipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION)
        rsaCipher.init(Cipher.ENCRYPT_MODE, peerPublicKey, OAEP_PARAMS)
        val wrappedKey = rsaCipher.doFinal(aesKey.encoded)

        // Pack as base64(wrappedKey) : base64(iv) : base64(cipherText)
        return listOf(wrappedKey, iv, cipherBytes)
            .joinToString(":") { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    /** Decrypts a blob produced by [encryptForPeer] using our own private key. Returns null if it can't be opened. */
    fun decryptMine(blob: String): String? {
        return try {
            val parts = blob.split(":")
            if (parts.size != 3) return null
            val wrappedKey = Base64.decode(parts[0], Base64.NO_WRAP)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[2], Base64.NO_WRAP)

            val rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION)
            rsaCipher.init(Cipher.DECRYPT_MODE, myPrivateKey, OAEP_PARAMS)
            val aesKeyBytes = rsaCipher.doFinal(wrappedKey)
            val aesKey: SecretKey = SecretKeySpec(aesKeyBytes, "AES")

            val aesCipher = Cipher.getInstance(AES_TRANSFORMATION)
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(aesCipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodePublicKey(base64: String): PublicKey {
        val keyBytes = Base64.decode(base64, Base64.NO_WRAP)
        return keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))
    }
}
