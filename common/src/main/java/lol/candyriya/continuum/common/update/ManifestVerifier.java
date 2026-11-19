package lol.candyriya.continuum.common.update;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 verification for the detached manifest signature (JEP 339, JDK 15+).
 *
 * <p>The manifest is verified as the exact bytes that were downloaded - the
 * signature never covers a re-serialized copy. The embedded public key is
 * the trust anchor; without one the updater is disabled rather than lenient.
 * Verify-then-parse: callers must check {@link Result#VALID} before trusting
 * a single field of the parsed manifest.</p>
 */
public final class ManifestVerifier {

    public enum Result {
        /** Signature valid for the exact manifest bytes under the embedded key. */
        VALID,
        /** Base64 payload was not 64 bytes. */
        MALFORMED_SIGNATURE,
        /** Signature did not verify under the embedded key. */
        INVALID_SIGNATURE,
        /** No public key is embedded in this jar; updates are disabled. */
        NO_PUBLIC_KEY,
        /** The embedded key is present but cannot be used (wrong algorithm/encoding). */
        KEY_UNREADABLE
    }

    /** Size Ed25519 signatures always are. */
    public static final int SIGNATURE_BYTES = 64;

    private final PublicKeySource keySource;

    public ManifestVerifier(PublicKeySource keySource) {
        this.keySource = keySource;
    }

    /**
     * Verifies {@code manifestBytes} against {@code signatureBase64}.
     * Never throws; every failure mode is a named {@link Result}.
     */
    public Result verify(byte[] manifestBytes, String signatureBase64) {
        if (manifestBytes == null || manifestBytes.length == 0) {
            return Result.INVALID_SIGNATURE;
        }
        byte[] keyDer = keySource.publicKeyDer();
        if (keyDer == null || keyDer.length == 0) {
            return Result.NO_PUBLIC_KEY;
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureBase64 == null ? "" : signatureBase64.trim());
        } catch (IllegalArgumentException e) {
            return Result.MALFORMED_SIGNATURE;
        }
        if (signature.length != SIGNATURE_BYTES) {
            return Result.MALFORMED_SIGNATURE;
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            PublicKey key = factory.generatePublic(new X509EncodedKeySpec(keyDer));
            Signature engine = Signature.getInstance("Ed25519");
            engine.initVerify(key);
            engine.update(manifestBytes);
            return engine.verify(signature) ? Result.VALID : Result.INVALID_SIGNATURE;
        } catch (java.security.spec.InvalidKeySpecException | java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            return Result.KEY_UNREADABLE;
        } catch (java.security.SignatureException e) {
            return Result.INVALID_SIGNATURE;
        } catch (RuntimeException e) {
            return Result.INVALID_SIGNATURE;
        }
    }
}
