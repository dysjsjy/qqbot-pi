package dev.qqbot.channel;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.NamedParameterSpec;
import java.util.HexFormat;

/** QQ's documented Ed25519 seed is the repeated UTF-8 AppSecret, truncated to 32 bytes. */
public final class OfficialQqSignature {
    private final KeyPair keys;

    public OfficialQqSignature(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("QQ_APP_SECRET is required");
        }
        byte[] input = secret.getBytes(StandardCharsets.UTF_8), seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = input[i % input.length];
        }
        try {
            var generator = KeyPairGenerator.getInstance("Ed25519", "SunEC");
            // SunEC's Ed25519 generator consumes exactly the 32-byte private seed (JDK 17/21).
            generator.initialize(
                    NamedParameterSpec.ED25519,
                    new SecureRandom() {
                        @Override
                        public void nextBytes(byte[] bytes) {
                            if (bytes.length != seed.length) {
                                throw new IllegalStateException("Unexpected Ed25519 seed length");
                            }
                            System.arraycopy(seed, 0, bytes, 0, seed.length);
                        }
                    });
            keys = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is unavailable", e);
        }
    }

    public String sign(String timestamp, byte[] body) {
        try {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(keys.getPrivate());
            signer.update(timestamp.getBytes(StandardCharsets.UTF_8));
            signer.update(body);
            return HexFormat.of().formatHex(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("QQ signature failed", e);
        }
    }

    public boolean verify(String timestamp, byte[] body, String signature) {
        if (timestamp == null
                || timestamp.isBlank()
                || signature == null
                || signature.length() != 128) {
            return false;
        }
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(keys.getPublic());
            verifier.update(timestamp.getBytes(StandardCharsets.UTF_8));
            verifier.update(body);
            return verifier.verify(HexFormat.of().parseHex(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
