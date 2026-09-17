/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.platform.app.InstrumentationRegistry;

import junit.framework.TestCase;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exercises real backend setters and real NIO storage, without starting lifecycle/sysfs work. */
public final class ThermalBackendPersistenceTest extends TestCase {
    private static final String PACKAGE = "me.sandai.test";

    public void testEnabledCommitFailures() throws Exception {
        checkFailedCommit(false, false);
        checkFailedCommit(false, true);
    }

    public void testAppProfileCommitFailures() throws Exception {
        checkFailedCommit(true, false);
        checkFailedCommit(true, true);
    }

    public void testPerformanceModeMigrationAndIndependentSaves() throws Exception {
        Path directory = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "thermal-mode-");
        Path base = directory.resolve("dash-thermal.json");
        Handler handler = new Handler(Looper.getMainLooper());
        try {
            new ThermalConfigStore(base).write(("{\"version\":1,\"enabled\":true,"
                    + "\"overrides\":{}}").getBytes(StandardCharsets.UTF_8));
            int user = UserHandle.myUserId();
            Context context = mock(Context.class);
            UserManager users = mock(UserManager.class);
            PackageManager packages = mock(PackageManager.class);
            when(context.getSystemServiceName(UserManager.class)).thenReturn(Context.USER_SERVICE);
            when(context.getSystemService(Context.USER_SERVICE)).thenReturn(users);
            when(context.getPackageManager()).thenReturn(packages);
            when(users.getUserInfo(user)).thenReturn(new UserInfo(user, "test", 0));
            when(packages.checkSignatures(Process.SYSTEM_UID, Binder.getCallingUid()))
                    .thenReturn(PackageManager.SIGNATURE_MATCH);
            when(packages.getApplicationInfoAsUser(PACKAGE, 0, user))
                    .thenReturn(new ApplicationInfo());
            ThermalBackend backend = new ThermalBackend(context, handler,
                    ignored -> new ThermalConfigStore(base));
            assertFalse(backend.getState(user).getBoolean("performanceMode"));
            backend.setPerformanceMode(user, true);
            backend.setAppProfile(user, PACKAGE, 25);
            backend.setEnabled(user, false);
            backend = new ThermalBackend(context, handler, ignored -> new ThermalConfigStore(base));
            assertTrue(backend.getState(user).getBoolean("performanceMode"));
            assertFalse(backend.getState(user).getBoolean("enabled"));
            backend.setPerformanceMode(user, false);
            assertEquals(25, backend.getAppPolicy(user, PACKAGE).getInt("selectedProfile"));
            for (int id : new int[] {0, 1, 6, 7, 9, 10, 11, 12, 15, 19, 20, 25}) {
                backend.setAppProfile(user, PACKAGE, id);
                for (boolean performance : new boolean[] {true, false}) {
                    backend.setPerformanceMode(user, performance);
                    backend = new ThermalBackend(context, handler,
                            ignored -> new ThermalConfigStore(base));
                    assertEquals(id, backend.getAppPolicy(user, PACKAGE).getInt("overrideProfile"));
                    assertEquals(id, backend.getAppPolicy(user, PACKAGE).getInt("selectedProfile"));
                }
            }
            backend.setAppProfile(user, PACKAGE, ThermalProfiles.AUTO);
            assertEquals(0, backend.getAppPolicy(user, PACKAGE).getInt("selectedProfile"));
            backend.setPerformanceMode(user, true);
            assertEquals(0, backend.getAppPolicy(user, PACKAGE).getInt("selectedProfile"));
        } finally {
            handler.removeCallbacksAndMessages(null);
            Files.deleteIfExists(base);
            Files.deleteIfExists(base.resolveSibling("dash-thermal.json.new"));
            Files.deleteIfExists(directory);
        }
    }

    private void checkFailedCommit(boolean appProfile, boolean afterRename) throws Exception {
        Path directory = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "thermal-backend-");
        Path base = directory.resolve("dash-thermal.json");
        Handler handler = new Handler(Looper.getMainLooper());
        try {
            new ThermalConfigStore(base).write(("{\"version\":1,\"enabled\":true,"
                    + "\"overrides\":{}}").getBytes(StandardCharsets.UTF_8));
            FailingStore store = new FailingStore(base, afterRename);
            int user = UserHandle.myUserId();
            Context context = mock(Context.class);
            UserManager users = mock(UserManager.class);
            PackageManager packages = mock(PackageManager.class);
            // The Class overload is final; use its actual service-name dispatch.
            when(context.getSystemServiceName(UserManager.class)).thenReturn(Context.USER_SERVICE);
            when(context.getSystemService(Context.USER_SERVICE)).thenReturn(users);
            when(context.getPackageManager()).thenReturn(packages);
            when(users.getUserInfo(user)).thenReturn(new UserInfo(user, "test", 0));
            when(packages.checkSignatures(Process.SYSTEM_UID, Binder.getCallingUid()))
                    .thenReturn(PackageManager.SIGNATURE_MATCH);
            when(packages.getApplicationInfoAsUser(PACKAGE, 0, user))
                    .thenReturn(new ApplicationInfo());
            ThermalBackend backend = new ThermalBackend(context, handler, ignored -> store);
            assertTrue(backend.getState(user).getBoolean("enabled"));
            assertEquals(1, store.reads);
            assertEquals(ThermalProfiles.AUTO,
                    backend.getAppPolicy(user, PACKAGE).getInt("overrideProfile"));

            try {
                setPolicy(backend, user, appProfile);
                fail("Setter acknowledged a failed commit");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getCause() instanceof IOException);
                if (afterRename) {
                    assertTrue(expected.getCause().getMessage().contains("Persistence unconfirmed"));
                }
            }
            // Loading again must read authoritative disk, not old or optimistically published cache.
            Bundle state = backend.getState(user);
            assertEquals(2, store.reads);
            assertEquals(appProfile || !afterRename, state.getBoolean("enabled"));
            assertEquals(appProfile && afterRename ? 19 : ThermalProfiles.AUTO,
                    backend.getAppPolicy(user, PACKAGE).getInt("overrideProfile"));
            String diagnostic = state.getString("persistenceError");
            assertNotNull(diagnostic);
            assertFalse(diagnostic.isEmpty());
            assertEquals(diagnostic, backend.getState(user).getString("persistenceError"));
            assertFalse(state.getBoolean("ready"));
            assertEquals(-1, state.getInt("requestedProfile"));

            store.failing = false;
            setPolicy(backend, user, appProfile);
            assertEquals("", backend.getState(user).getString("persistenceError"));
        } finally {
            handler.removeCallbacksAndMessages(null);
            Files.deleteIfExists(directory.resolve("dash-thermal.json.new"));
            Files.deleteIfExists(directory.resolve("dash-thermal.json.bak"));
            Files.deleteIfExists(base);
            Files.delete(directory);
        }
    }

    private static void setPolicy(ThermalBackend backend, int user, boolean appProfile) {
        if (appProfile) backend.setAppProfile(user, PACKAGE, 19);
        else backend.setEnabled(user, false);
    }

    private static final class FailingStore extends ThermalConfigStore {
        final boolean afterRename;
        boolean failing = true;
        int reads;

        FailingStore(Path path, boolean afterRename) {
            super(path);
            this.afterRename = afterRename;
        }

        @Override byte[] read() throws IOException {
            reads++;
            return super.read();
        }

        @Override void forceFile(FileChannel file) throws IOException {
            if (failing && !afterRename) throw new IOException("Injected file sync failure");
            super.forceFile(file);
        }

        @Override void forceDirectory(FileChannel directory) throws IOException {
            if (failing && afterRename) throw new IOException("Injected directory sync failure");
            super.forceDirectory(directory);
        }
    }
}
