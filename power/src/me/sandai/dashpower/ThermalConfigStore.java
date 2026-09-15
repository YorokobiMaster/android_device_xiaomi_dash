/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Caller serializes access. A successful write acknowledges durable storage, not actuation. */
class ThermalConfigStore {
    private final Path base;
    private final Path pending;
    private final Path backup;

    ThermalConfigStore(Path base) {
        this.base = base.toAbsolutePath();
        pending = this.base.resolveSibling(this.base.getFileName() + ".new");
        backup = this.base.resolveSibling(this.base.getFileName() + ".bak");
    }

    byte[] read() throws IOException {
        recover();
        return present(base) ? Files.readAllBytes(base) : null;
    }

    void write(byte[] bytes) throws IOException {
        recover();
        boolean published = false;
        // Open the directory before touching the candidate, never after publishing it.
        try (FileChannel directory = FileChannel.open(base.getParent(), StandardOpenOption.READ)) {
            try (FileChannel file = FileChannel.open(pending,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW),
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    if (writeChunk(file, buffer) <= 0) throw new IOException("Write made no progress");
                }
                forceFile(file);
            }
            move(pending, base);
            published = true;
            forceDirectory(directory);
        } catch (IOException e) {
            if (published) throw uncertain(e);
            // An incomplete .new is never authoritative; recover() discards it on the next call.
            throw e;
        }
    }

    private void recover() throws IOException {
        if (present(backup)) {
            boolean published = false;
            try (FileChannel directory = FileChannel.open(base.getParent(), StandardOpenOption.READ)) {
                Files.setPosixFilePermissions(backup, PosixFilePermissions.fromString("rw-------"));
                try (FileChannel file = FileChannel.open(backup, StandardOpenOption.READ)) {
                    forceFile(file);
                }
                move(backup, base);
                published = true;
                forceDirectory(directory);
            } catch (IOException e) {
                if (published) throw uncertain(e);
                throw e;
            }
        }
        Files.deleteIfExists(pending);
    }

    private static boolean present(Path path) throws IOException {
        try {
            if (!Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                throw new IOException("Not a regular configuration file: " + path);
            }
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    private IOException uncertain(IOException cause) {
        return new IOException("Persistence unconfirmed for " + base
                + ": replacement is visible but directory sync/close failed; no rollback", cause);
    }

    // Package-private fault seams; production still uses real NIO files and channels throughout.
    int writeChunk(FileChannel file, ByteBuffer buffer) throws IOException {
        return file.write(buffer);
    }

    void forceFile(FileChannel file) throws IOException {
        file.force(true);
    }

    void move(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    void forceDirectory(FileChannel directory) throws IOException {
        directory.force(true);
    }
}
