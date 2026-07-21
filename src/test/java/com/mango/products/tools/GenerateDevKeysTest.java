package com.mango.products.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GenerateDevKeysTest {
    private static final Path SOURCE =
            Path.of("tools", "jwt", "GenerateDevKeys.java").toAbsolutePath();
    private static final String JAVA = Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java"
    ).toString();

    @TempDir
    Path directory;

    @Test
    void generatesMatchingPkcs8AndX509PemFilesWithoutPrintingKeyMaterial() throws Exception {
        Execution result = execute();

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout())
                .contains("Generated development RSA key pair.")
                .contains("Development only.")
                .doesNotContain("BEGIN PRIVATE KEY", "BEGIN PUBLIC KEY");
        assertThat(result.stderr()).doesNotContain("BEGIN PRIVATE KEY", "BEGIN PUBLIC KEY");

        Path privateKey = privateKey();
        Path publicKey = publicKey();
        assertThat(privateKey).exists();
        assertThat(publicKey).exists();
        assertThat(privateKey.getParent()).isDirectory();
        assertThat(publicKey.getParent()).isDirectory();

        String privatePem = Files.readString(privateKey);
        String publicPem = Files.readString(publicKey);
        assertThat(privatePem)
                .startsWith("-----BEGIN PRIVATE KEY-----")
                .endsWith("-----END PRIVATE KEY-----" + System.lineSeparator());
        assertThat(publicPem)
                .startsWith("-----BEGIN PUBLIC KEY-----")
                .endsWith("-----END PUBLIC KEY-----" + System.lineSeparator());

        assertPairMatches(readPrivateKey(privatePem), readPublicKey(publicPem));
    }

    @Test
    void existingCompletePairIsNotOverwrittenWithoutForce() throws Exception {
        assertThat(execute().exitCode()).isZero();
        byte[] privateBefore = Files.readAllBytes(privateKey());
        byte[] publicBefore = Files.readAllBytes(publicKey());

        Execution second = execute();

        assertThat(second.exitCode()).isZero();
        assertThat(second.stdout()).contains("already exists; no files were changed");
        assertThat(Files.readAllBytes(privateKey())).isEqualTo(privateBefore);
        assertThat(Files.readAllBytes(publicKey())).isEqualTo(publicBefore);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void incompletePairFailsWithoutChangingRemainingKey(boolean removePrivate) throws Exception {
        assertThat(execute().exitCode()).isZero();
        Path remaining = removePrivate ? publicKey() : privateKey();
        Path removed = removePrivate ? privateKey() : publicKey();
        Files.delete(removed);
        byte[] remainingBefore = Files.readAllBytes(remaining);

        Execution result = execute();

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("key pair is incomplete").contains("--force");
        assertThat(remaining).exists();
        assertThat(Files.readAllBytes(remaining)).isEqualTo(remainingBefore);
        assertThat(removed).doesNotExist();

        Execution forced = execute("--force");
        assertThat(forced.exitCode()).isZero();
        assertThat(privateKey()).exists();
        assertThat(publicKey()).exists();
        assertPairMatches(
                readPrivateKey(Files.readString(privateKey())),
                readPublicKey(Files.readString(publicKey()))
        );
    }

    @Test
    void forceRegeneratesBothFilesAsOneMatchingPair() throws Exception {
        assertThat(execute().exitCode()).isZero();
        byte[] privateBefore = Files.readAllBytes(privateKey());
        byte[] publicBefore = Files.readAllBytes(publicKey());

        Execution forced = execute("--force");

        assertThat(forced.exitCode()).isZero();
        assertThat(Files.readAllBytes(privateKey())).isNotEqualTo(privateBefore);
        assertThat(Files.readAllBytes(publicKey())).isNotEqualTo(publicBefore);
        assertPairMatches(
                readPrivateKey(Files.readString(privateKey())),
                readPublicKey(Files.readString(publicKey()))
        );
    }

    private Execution execute(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(JAVA);
        command.add(SOURCE.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new Execution(exitCode, stdout, stderr);
    }

    private Path privateKey() {
        return directory.resolve("tools/jwt/generated/dev-private-key.pem");
    }

    private Path publicKey() {
        return directory.resolve("config/jwt/generated/dev-public-key.pem");
    }

    private RSAPrivateKey readPrivateKey(String pem) throws Exception {
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(decodePem(pem, "PRIVATE KEY")));
    }

    private RSAPublicKey readPublicKey(String pem) throws Exception {
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(decodePem(pem, "PUBLIC KEY")));
    }

    private byte[] decodePem(String pem, String type) {
        String encoded = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(encoded);
    }

    private void assertPairMatches(RSAPrivateKey privateKey, RSAPublicKey publicKey)
            throws Exception {
        byte[] message = "pair-validation".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(message);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(message);
        assertThat(verifier.verify(signature)).isTrue();
        assertThat(publicKey.getModulus().bitLength()).isGreaterThanOrEqualTo(2048);
    }

    private record Execution(int exitCode, String stdout, String stderr) {
    }
}
