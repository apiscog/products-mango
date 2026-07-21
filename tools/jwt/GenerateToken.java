import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

public final class GenerateToken {
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private GenerateToken() {
    }

    public static void main(String[] args) {
        try {
            System.out.println(generate(args));
        }
        catch (MissingPrivateKeyException exception) {
            System.err.println("java tools/jwt/GenerateDevKeys.java");
            System.err.println("Run the command above first. Private key not found: "
                    + exception.path());
            System.exit(1);
        }
        catch (Exception exception) {
            System.err.println("Token generation failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static String generate(String[] args) throws Exception {
        if (args.length != 1 || !("reader".equals(args[0]) || "writer".equals(args[0]))) {
            throw new IllegalArgumentException("usage: GenerateToken reader OR writer");
        }
        String kind = args[0];
        String issuer = environment("JWT_ISSUER", "products-challenge-dev");
        String audience = environment("JWT_AUDIENCE", "products-api");
        String keyLocation = environment(
                "JWT_PRIVATE_KEY_LOCATION",
                "tools/jwt/generated/dev-private-key.pem"
        );
        long ttlSeconds = positiveLong(environment("JWT_TOKEN_TTL_SECONDS", "900"));
        String subject = "reader".equals(kind) ? "evaluator-reader" : "evaluator-writer";
        String scope = "reader".equals(kind) ? "products.read" : "products.read products.write";
        return sign(subject, issuer, audience, scope, ttlSeconds, keyLocation);
    }

    private static String sign(
            String subject, String issuer, String audience, String scope,
            long ttlSeconds, String keyLocation
    ) throws Exception {
        Instant now = Instant.now();
        String header = """
                {"alg":"RS256","typ":"JWT"}""";
        String payload = """
                {"sub":"%s","iss":"%s","aud":"%s","scope":"%s","iat":%d,"nbf":%d,"exp":%d}""".formatted(
                safeClaim(subject), safeClaim(issuer), safeClaim(audience), safeClaim(scope),
                now.getEpochSecond(), now.minusSeconds(5).getEpochSecond(),
                now.plusSeconds(ttlSeconds).getEpochSecond());
        String signingInput = encode(header) + "." + encode(payload);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(readPrivateKey(Path.of(keyLocation)));
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + BASE64_URL.encodeToString(signer.sign());
    }

    private static RSAPrivateKey readPrivateKey(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new MissingPrivateKeyException(path);
        }
        String pem = Files.readString(path, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    private static String encode(String value) {
        return BASE64_URL.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static long positiveLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed <= 0 || parsed > 3600) {
            throw new IllegalArgumentException("JWT_TOKEN_TTL_SECONDS must be between 1 and 3600");
        }
        return parsed;
    }

    private static String safeClaim(String value) {
        if (!value.matches("[A-Za-z0-9 ._:/-]+")) {
            throw new IllegalArgumentException("JWT claim values contain unsupported characters");
        }
        return value;
    }

    private static final class MissingPrivateKeyException extends IllegalStateException {
        private final Path path;

        private MissingPrivateKeyException(Path path) {
            this.path = path;
        }

        private Path path() {
            return path;
        }
    }
}
