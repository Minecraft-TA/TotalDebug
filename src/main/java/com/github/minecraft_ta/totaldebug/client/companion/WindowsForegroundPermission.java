package com.github.minecraft_ta.totaldebug.client.companion;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;

final class WindowsForegroundPermission implements CompanionForegroundPermission {
    private static final long MAX_WINDOWS_PROCESS_ID = 0xFFFF_FFFFL;

    private final NativeApi nativeApi;

    WindowsForegroundPermission(NativeApi nativeApi) {
        this.nativeApi = nativeApi;
    }

    static CompanionForegroundPermission currentPlatform() {
        if (!Platform.isWindows()) {
            return processId -> {
                throw new IOException("Companion window activation is currently supported on Windows only");
            };
        }
        User32 user32 = Native.load("user32", User32.class);
        return new WindowsForegroundPermission(new NativeApi() {
            @Override
            public boolean allowSetForegroundWindow(int processId) {
                return user32.AllowSetForegroundWindow(processId);
            }

            @Override
            public int lastError() {
                return Native.getLastError();
            }
        });
    }

    @Override
    public void grantTo(long processId) throws IOException {
        if (processId <= 0L || processId > MAX_WINDOWS_PROCESS_ID) {
            throw new IOException("Companion process ID is outside the Windows DWORD range: " + processId);
        }
        if (!this.nativeApi.allowSetForegroundWindow((int) processId)) {
            throw new IOException(
                    "Windows refused to grant foreground activation to Companion process "
                            + processId
                            + " (error "
                            + this.nativeApi.lastError()
                            + ")"
            );
        }
    }

    interface NativeApi {
        boolean allowSetForegroundWindow(int processId);

        int lastError();
    }

    private interface User32 extends StdCallLibrary {
        boolean AllowSetForegroundWindow(int processId);
    }
}
