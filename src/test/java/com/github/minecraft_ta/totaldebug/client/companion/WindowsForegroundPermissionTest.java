package com.github.minecraft_ta.totaldebug.client.companion;

import com.sun.jna.Platform;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsForegroundPermissionTest {
    @Test
    void grantsTheExactCompanionProcessId() throws Exception {
        List<Integer> processIds = new ArrayList<>();
        WindowsForegroundPermission permission = new WindowsForegroundPermission(new NativeApiStub() {
            @Override
            public boolean allowSetForegroundWindow(int processId) {
                processIds.add(processId);
                return true;
            }
        });

        permission.grantTo(42L);

        assertEquals(List.of(42), processIds);
    }

    @Test
    void reportsARejectedNativeGrantExactly() {
        WindowsForegroundPermission permission = new WindowsForegroundPermission(new NativeApiStub() {
            @Override
            public boolean allowSetForegroundWindow(int processId) {
                return false;
            }

            @Override
            public int lastError() {
                return 5;
            }
        });

        IOException failure = assertThrows(IOException.class, () -> permission.grantTo(42L));

        assertEquals(
                "Windows refused to grant foreground activation to Companion process 42 (error 5)",
                failure.getMessage()
        );
    }

    @Test
    void rejectsAProcessIdOutsideTheWindowsDwordRange() {
        WindowsForegroundPermission permission = new WindowsForegroundPermission(new NativeApiStub());

        IOException failure = assertThrows(IOException.class, () -> permission.grantTo(0x1_0000_0000L));

        assertEquals(
                "Companion process ID is outside the Windows DWORD range: 4294967296",
                failure.getMessage()
        );
    }

    @Test
    void windowsBindingResolvesTheNativeFunction() {
        assumeTrue(Platform.isWindows());

        assertDoesNotThrow(() -> {
            try {
                WindowsForegroundPermission.currentPlatform().grantTo(ProcessHandle.current().pid());
            } catch (IOException ignored) {
            }
        });
    }

    private static class NativeApiStub implements WindowsForegroundPermission.NativeApi {
        @Override
        public boolean allowSetForegroundWindow(int processId) {
            return true;
        }

        @Override
        public int lastError() {
            return 0;
        }
    }
}
