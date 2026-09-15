/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Comparator;

/** Exercises the production NIO store with real files; no Android runtime required. */
public final class ThermalConfigStoreTest {
    private static final byte[] OLD = "{\"version\":1,\"enabled\":true,\"overrides\":{}}"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW = "{\"version\":1,\"enabled\":false,\"overrides\":{\"app\":50}}"
            .getBytes(StandardCharsets.UTF_8);
    private static int checks;

    private static void check(boolean value, String message) {
        ++checks;
        if (!value) throw new AssertionError(message);
    }

    private interface Operation { void run() throws IOException; }

    private static IOException fails(Operation operation) throws IOException {
        try {
            operation.run();
        } catch (IOException e) {
            ++checks;
            return e;
        }
        throw new AssertionError("Expected IOException");
    }

    private static final class FaultStore extends ThermalConfigStore {
        String fault = "";
        FileChannel candidate;
        int chunks;
        boolean forced;

        FaultStore(Path path) { super(path); }

        @Override int writeChunk(FileChannel file, ByteBuffer buffer) throws IOException {
            candidate = file;
            ++chunks;
            if (fault.equals("write") && chunks > 1) throw new IOException("injected write");
            if (fault.equals("zero")) return 0;
            int limit = buffer.limit();
            buffer.limit(Math.min(limit, buffer.position() + 3));
            try { return super.writeChunk(file, buffer); }
            finally { buffer.limit(limit); }
        }

        @Override void forceFile(FileChannel file) throws IOException {
            if (fault.equals("file-force")) throw new IOException("injected file force");
            super.forceFile(file);
            forced = true;
        }

        @Override void move(Path from, Path to) throws IOException {
            check(forced, "file forced before publication");
            if (candidate != null) check(!candidate.isOpen(), "candidate closed before publication");
            check(Files.getPosixFilePermissions(from).equals(
                    PosixFilePermissions.fromString("rw-------")), "candidate private before rename");
            if (fault.equals("rename")) {
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "injected");
            }
            super.move(from, to);
        }

        @Override void forceDirectory(FileChannel directory) throws IOException {
            if (fault.equals("directory-force")) throw new IOException("injected directory force");
            super.forceDirectory(directory);
        }
    }

    private static void run(Path dir) throws IOException {
        Path base = dir.resolve("dash-thermal.json");
        Path pending = dir.resolve("dash-thermal.json.new");
        Path backup = dir.resolve("dash-thermal.json.bak");
        ThermalConfigStore store = new ThermalConfigStore(base);
        check(store.read() == null, "absent base returns null");
        Files.write(pending, NEW);
        check(store.read() == null && !Files.exists(pending), "orphan new is never committed");
        store.write(OLD);
        check(Arrays.equals(store.read(), OLD), "first commit preserves exact JSON bytes");
        FaultStore shortWrites = new FaultStore(base);
        shortWrites.write(NEW);
        check(shortWrites.chunks > 1 && Arrays.equals(store.read(), NEW), "short writes complete");
        check(Files.getPosixFilePermissions(base).equals(
                PosixFilePermissions.fromString("rw-------")), "committed file is private");
        store.write(new byte[0]);
        check(store.read().length == 0, "empty data is distinct from missing base");

        for (String fault : new String[] {"write", "zero", "file-force", "rename"}) {
            store.write(OLD);
            FaultStore broken = new FaultStore(base);
            broken.fault = fault;
            IOException error = fails(() -> broken.write(NEW));
            check(!error.getMessage().contains("Persistence unconfirmed"), "pre-rename error: " + fault);
            check(Arrays.equals(Files.readAllBytes(base), OLD), "old base survives: " + fault);
            if (broken.candidate != null) check(!broken.candidate.isOpen(), "failure closes file");
            check(Arrays.equals(store.read(), OLD) && !Files.exists(pending), "failed new discarded");
        }
        FaultStore broken = new FaultStore(base);
        broken.fault = "directory-force";
        IOException error = fails(() -> broken.write(NEW));
        check(error.getMessage().contains("Persistence unconfirmed") && error.getCause() != null,
                "post-rename failure reports uncertainty and cause");
        check(Arrays.equals(store.read(), NEW), "post-rename failure does not pretend rollback");

        // Legacy AtomicFile backups take precedence even when both base and new exist.
        Files.write(backup, OLD);
        Files.write(pending, NEW);
        check(Arrays.equals(store.read(), OLD), "backup authoritative over base and new");
        check(!Files.exists(backup) && !Files.exists(pending), "recovery consumes remnants");
        check(Files.getPosixFilePermissions(base).equals(
                PosixFilePermissions.fromString("rw-------")), "recovered backup private");
        Files.delete(base);
        Files.write(backup, OLD);
        check(Arrays.equals(store.read(), OLD), "backup recovered without base");

        for (String fault : new String[] {"file-force", "rename", "directory-force"}) {
            Files.write(base, NEW);
            Files.write(backup, OLD);
            FaultStore recovery = new FaultStore(base);
            recovery.fault = fault;
            IOException recoveryError = fails(recovery::read);
            boolean published = fault.equals("directory-force");
            check(Arrays.equals(Files.readAllBytes(base), published ? OLD : NEW),
                    "recovery publication boundary: " + fault);
            check(Files.exists(backup) != published, "recovery backup retention: " + fault);
            check(recoveryError.getMessage().contains("Persistence unconfirmed") == published,
                    "recovery uncertainty boundary: " + fault);
            check(Arrays.equals(store.read(), OLD), "recovery retry gets authoritative data");
        }
        Files.write(backup, OLD);
        store.write(NEW);
        check(Arrays.equals(store.read(), NEW) && !Files.exists(backup), "write first recovers backup");

        Files.createDirectory(pending);
        Files.write(pending.resolve("blocker"), OLD);
        fails(store::read);
        fails(() -> store.write(NEW));
        check(Arrays.equals(Files.readAllBytes(base), NEW), "cleanup failure leaves base unchanged");
        Files.delete(pending.resolve("blocker"));
        Files.delete(pending);
        Files.createDirectory(backup);
        fails(store::read);
        Files.delete(backup);
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("---------"));
        try { fails(store::read); }
        finally { Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rw-------")); }
        Files.delete(base);
        Files.createDirectory(base);
        fails(store::read);
        fails(() -> store.write(NEW));
        Files.delete(base);
        Files.deleteIfExists(pending);
        Files.createSymbolicLink(base, dir.resolve("missing"));
        fails(store::read);
        Files.delete(base);

        ThermalConfigStore missingParent = new ThermalConfigStore(dir.resolve("absent/config"));
        check(missingParent.read() == null, "missing parent implies no saved base");
        fails(() -> missingParent.write(NEW));
        check(!Files.exists(dir.resolve("absent")), "store does not recreate a missing user directory");
    }

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory(Path.of(
                "tmp/disposable-anytime/dash-power-host-tests"), "thermal-store-").toAbsolutePath();
        try {
            run(dir);
            System.out.println("PASS: " + checks + " durable store checks");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
