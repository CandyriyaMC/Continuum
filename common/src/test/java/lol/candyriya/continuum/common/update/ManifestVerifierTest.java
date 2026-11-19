package lol.candyriya.continuum.common.update;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ManifestVerifierTest {

    private KeyPair keyPair;
    private byte[] manifestBytes;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        keyPair = generator.generateKeyPair();
        manifestBytes = "{\"schema\":1,\"build\":\"build-120\"}".getBytes(StandardCharsets.UTF_8);
    }

    private ManifestVerifier verifierFor(byte[] publicKeyDer) {
        return new ManifestVerifier(() -> publicKeyDer);
    }

    private String sign(byte[] payload) throws Exception {
        Signature engine = Signature.getInstance("Ed25519");
        engine.initSign(keyPair.getPrivate());
        engine.update(payload);
        return Base64.getEncoder().encodeToString(engine.sign());
    }

    @Test
    void validSignatureVerifies() throws Exception {
        ManifestVerifier verifier = verifierFor(keyPair.getPublic().getEncoded());
        assertEquals(ManifestVerifier.Result.VALID, verifier.verify(manifestBytes, sign(manifestBytes)));
    }

    @Test
    void tamperedManifestIsRejected() throws Exception {
        String signature = sign(manifestBytes);
        byte[] tampered = manifestBytes.clone();
        tampered[tampered.length - 1] = (byte) (tampered[tampered.length - 1] == (byte) '}' ? (byte) ']' : (byte) '}');
        ManifestVerifier verifier = verifierFor(keyPair.getPublic().getEncoded());
        assertEquals(ManifestVerifier.Result.INVALID_SIGNATURE, verifier.verify(tampered, signature));
    }

    @Test
    void signatureFromAnotherKeyIsRejected() throws Exception {
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ManifestVerifier verifier = verifierFor(other.getPublic().getEncoded());
        assertEquals(ManifestVerifier.Result.INVALID_SIGNATURE, verifier.verify(manifestBytes, sign(manifestBytes)));
    }

    @Test
    void garbageSignatureIsMalformed() {
        ManifestVerifier verifier = verifierFor(keyPair.getPublic().getEncoded());
        assertEquals(ManifestVerifier.Result.MALFORMED_SIGNATURE, verifier.verify(manifestBytes, "!!!"));
        assertEquals(ManifestVerifier.Result.MALFORMED_SIGNATURE,
                verifier.verify(manifestBytes, Base64.getEncoder().encodeToString(new byte[63])));
        assertEquals(ManifestVerifier.Result.MALFORMED_SIGNATURE,
                verifier.verify(manifestBytes, Base64.getEncoder().encodeToString(new byte[65])));
    }

    @Test
    void missingKeyDisablesVerification() throws Exception {
        ManifestVerifier verifier = verifierFor(null);
        assertEquals(ManifestVerifier.Result.NO_PUBLIC_KEY, verifier.verify(manifestBytes, sign(manifestBytes)));
    }

    @Test
    void unreadableKeyIsReportedNotThrown() throws Exception {
        ManifestVerifier verifier = verifierFor(new byte[]{1, 2, 3});
        assertEquals(ManifestVerifier.Result.KEY_UNREADABLE, verifier.verify(manifestBytes, sign(manifestBytes)));
    }

    @Test
    void roundtripBytesAreExact() throws Exception {
        // sign once, then prove the same bytes keep verifying and a flipped bit does not
        String signature = sign(manifestBytes);
        ManifestVerifier verifier = verifierFor(keyPair.getPublic().getEncoded());
        assertEquals(ManifestVerifier.Result.VALID, verifier.verify(manifestBytes, signature));
        byte[] oneBitOff = manifestBytes.clone();
        oneBitOff[0] = (byte) (oneBitOff[0] ^ 0x01);
        assertNotEquals(ManifestVerifier.Result.VALID, verifier.verify(oneBitOff, signature));
    }
}
