package lol.candyriya.continuum.common.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Loads the embedded manifest public key from {@code /continuum-manifest-public.key}.
 *
 * <p>Format: DER-encoded X.509 SubjectPublicKeyInfo for an Ed25519 key.
 * Missing resource or an unreadable key both come back as null - the updater
 * treats that as "no key embedded" and disables itself with one warning. The
 * key is read once and cached; {@link #reload()} exists for tests.</p>
 */
public final class ClasspathPublicKeySource implements PublicKeySource {

    /** Classpath location of the embedded public key. */
    public static final String RESOURCE = "/continuum-manifest-public.key";

    private static final int MAX_KEY_BYTES = 4096;

    private volatile byte[] cached;
    private volatile boolean loaded;

    private final ClassLoader classLoader;

    public ClasspathPublicKeySource() {
        this(ClasspathPublicKeySource.class.getClassLoader());
    }

    public ClasspathPublicKeySource(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public byte[] publicKeyDer() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    cached = read();
                    loaded = true;
                }
            }
        }
        return cached == null ? null : Arrays.copyOf(cached, cached.length);
    }

    /** Drops the cached key so the next {@link #publicKeyDer()} re-reads the classpath. */
    public synchronized void reload() {
        cached = null;
        loaded = false;
    }

    private byte[] read() {
        try (InputStream in = classLoader.getResourceAsStream(RESOURCE.substring(1))) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_KEY_BYTES) {
                    return null;
                }
                out.write(buffer, 0, read);
            }
            byte[] der = out.toByteArray();
            return der.length == 0 ? null : der;
        } catch (IOException e) {
            return null;
        }
    }
}
