import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

public final class GenerateDevKeys {
    private static final Path PRIVATE_KEY =
            Path.of("tools", "jwt", "generated", "dev-private-key.pem");
    private static final Path PUBLIC_KEY =
            Path.of("config", "jwt", "generated", "dev-public-key.pem");
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private GenerateDevKeys() {
    }

    public static void main(String[] args) {
        try {
            boolean force = parseForce(args);
            generate(force);
        }
        catch (Exception exception) {
            System.err.println("Development key generation failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static boolean parseForce(String[] args) {
        if (args.length == 0) {
            return false;
        }
        if (args.length == 1 && "--force".equals(args[0])) {
            return true;
        }
        throw new IllegalArgumentException("usage: GenerateDevKeys [--force]");
    }

    private static void generate(boolean force) throws Exception {
        boolean privateExists = Files.exists(PRIVATE_KEY);
        boolean publicExists = Files.exists(PUBLIC_KEY);

        if (privateExists != publicExists && !force) {
            throw new IllegalStateException(
                    "the development key pair is incomplete; restore both files or run "
                            + "java tools/jwt/GenerateDevKeys.java --force");
        }
        if (privateExists && !force) {
            printExistingPair();
            return;
        }

        Files.createDirectories(PRIVATE_KEY.getParent());
        Files.createDirectories(PUBLIC_KEY.getParent());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        Path privateTemporary = Files.createTempFile(
                PRIVATE_KEY.getParent(), ".dev-private-key-", ".tmp");
        Path publicTemporary = Files.createTempFile(
                PUBLIC_KEY.getParent(), ".dev-public-key-", ".tmp");
        try {
            writePem(privateTemporary, "PRIVATE KEY", keyPair.getPrivate().getEncoded());
            writePem(publicTemporary, "PUBLIC KEY", keyPair.getPublic().getEncoded());
            restrictPrivateKey(privateTemporary);
            replacePair(privateTemporary, publicTemporary, privateExists, publicExists);
        }
        finally {
            Files.deleteIfExists(privateTemporary);
            Files.deleteIfExists(publicTemporary);
        }

        printGeneratedPair();
    }

    private static void writePem(Path path, String type, byte[] encoded) throws IOException {
        Base64.Encoder encoder = Base64.getMimeEncoder(
                64, System.lineSeparator().getBytes(StandardCharsets.US_ASCII));
        String pem = "-----BEGIN " + type + "-----" + System.lineSeparator()
                + encoder.encodeToString(encoded) + System.lineSeparator()
                + "-----END " + type + "-----" + System.lineSeparator();
        Files.writeString(path, pem, StandardCharsets.UTF_8);
    }

    private static void restrictPrivateKey(Path path) {
        try {
            Files.setPosixFilePermissions(path, PRIVATE_PERMISSIONS);
        }
        catch (UnsupportedOperationException | IOException exception) {
            System.err.println(
                    "Warning: owner-only POSIX permissions are not supported for the private key.");
        }
    }

    private static void replacePair(
            Path privateTemporary,
            Path publicTemporary,
            boolean privateExists,
            boolean publicExists
    ) throws IOException {
        String suffix = ".backup-" + UUID.randomUUID();
        Path privateBackup = PRIVATE_KEY.resolveSibling(PRIVATE_KEY.getFileName() + suffix);
        Path publicBackup = PUBLIC_KEY.resolveSibling(PUBLIC_KEY.getFileName() + suffix);
        boolean privateBackedUp = false;
        boolean publicBackedUp = false;
        boolean privateInstalled = false;
        boolean publicInstalled = false;

        try {
            if (privateExists) {
                move(PRIVATE_KEY, privateBackup, false);
                privateBackedUp = true;
            }
            if (publicExists) {
                move(PUBLIC_KEY, publicBackup, false);
                publicBackedUp = true;
            }

            move(privateTemporary, PRIVATE_KEY, false);
            privateInstalled = true;
            move(publicTemporary, PUBLIC_KEY, false);
            publicInstalled = true;
        }
        catch (IOException exception) {
            if (publicInstalled) {
                Files.deleteIfExists(PUBLIC_KEY);
            }
            if (privateInstalled) {
                Files.deleteIfExists(PRIVATE_KEY);
            }
            if (publicBackedUp) {
                move(publicBackup, PUBLIC_KEY, false);
            }
            if (privateBackedUp) {
                move(privateBackup, PRIVATE_KEY, false);
            }
            throw exception;
        }
        finally {
            Files.deleteIfExists(privateBackup);
            Files.deleteIfExists(publicBackup);
        }
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        StandardCopyOption[] atomicOptions = replace
                ? new StandardCopyOption[] {
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                }
                : new StandardCopyOption[] {StandardCopyOption.ATOMIC_MOVE};
        StandardCopyOption[] fallbackOptions = replace
                ? new StandardCopyOption[] {StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[0];
        try {
            Files.move(source, target, atomicOptions);
        }
        catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, fallbackOptions);
        }
    }

    private static void printGeneratedPair() {
        System.out.println("Generated development RSA key pair.");
        printLocations();
    }

    private static void printExistingPair() {
        System.out.println("Development RSA key pair already exists; no files were changed.");
        printLocations();
    }

    private static void printLocations() {
        System.out.println("Private key: " + PRIVATE_KEY);
        System.out.println("Public key: " + PUBLIC_KEY);
        System.out.println();
        System.out.println("Development only. Do not use these keys in production.");
    }
}
