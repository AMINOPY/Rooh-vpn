package com.aminopy.roohvpn.vpn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AetherNativeRuntimeTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void aetherWarpDoesNotRequireTheOptionalPsiphonBinary() throws IOException {
        File nativeDir = temporaryFolder.newFolder("native");
        executable(nativeDir, "libaether.so");
        regularFile(nativeDir, "libaethertun.so");
        regularFile(nativeDir, "libhev-socks5-tunnel.so");

        assertTrue(AetherNativeRuntime.isAetherAvailable(nativeDir));
        assertFalse(AetherNativeRuntime.isPsiphonAvailable(nativeDir));
    }

    @Test
    public void psiphonIsReportedOnlyWhenItsExecutableIsPresent() throws IOException {
        File nativeDir = temporaryFolder.newFolder("native");
        regularFile(nativeDir, "libpsiphon.so");
        assertFalse(AetherNativeRuntime.isPsiphonAvailable(nativeDir));

        File psiphon = new File(nativeDir, "libpsiphon.so");
        assertTrue(psiphon.setExecutable(true, true));
        assertTrue(AetherNativeRuntime.isPsiphonAvailable(nativeDir));
    }

    private static void executable(File dir, String name) throws IOException {
        File file = regularFile(dir, name);
        assertTrue(file.setExecutable(true, true));
    }

    private static File regularFile(File dir, String name) throws IOException {
        File file = new File(dir, name);
        assertTrue(file.createNewFile());
        return file;
    }
}
