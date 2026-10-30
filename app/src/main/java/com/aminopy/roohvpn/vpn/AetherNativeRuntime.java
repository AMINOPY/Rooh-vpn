package com.aminopy.roohvpn.vpn;

import android.content.Context;

import java.io.File;

/**
 * ABI-local native runtime inspection.
 *
 * Aether/WARP and Psiphon are separate capabilities: Aether must not be
 * disabled merely because the optional Psiphon executable is absent.
 */
public final class AetherNativeRuntime {
    private static final String AETHER = "libaether.so";
    private static final String TUN_BRIDGE = "libaethertun.so";
    private static final String HEV = "libhev-socks5-tunnel.so";
    private static final String PSIPHON = "libpsiphon.so";

    private AetherNativeRuntime() {}

    private static File library(Context context, String name) {
        return new File(context.getApplicationInfo().nativeLibraryDir, name);
    }

    public static boolean isAetherAvailable(Context context) {
        return library(context, AETHER).isFile()
                && library(context, AETHER).canExecute()
                && library(context, TUN_BRIDGE).isFile()
                && library(context, HEV).isFile();
    }

    public static boolean isPsiphonAvailable(Context context) {
        return library(context, PSIPHON).isFile()
                && library(context, PSIPHON).canExecute();
    }

    public static String psiphonExecutable(Context context) {
        File executable = library(context, PSIPHON);
        return isPsiphonAvailable(context) ? executable.getAbsolutePath() : null;
    }
}
