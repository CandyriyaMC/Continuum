package lol.candyriya.continuum.common.update;

/**
 * Where the embedded Ed25519 public key comes from. The release-signing
 * private key lives in the release pipeline's secrets; the matching public
 * key ships inside the plugin and is the anchor for everything the updater
 * trusts - TLS alone is not.
 *
 * <p>Implementations return DER-encoded X.509 {@code SubjectPublicKeyInfo}
 * bytes ({@code KeyFactory#getInstance("Ed25519")} +
 * {@code X509EncodedKeySpec}), or null when no key is embedded.</p>
 */
@FunctionalInterface
public interface PublicKeySource {

    /**
     * @return DER-encoded SubjectPublicKeyInfo for the manifest-signing key,
     *         or null when the plugin was built without one
     */
    byte[] publicKeyDer();
}
