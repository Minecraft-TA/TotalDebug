package com.github.minecraft_ta.totalDebugCompanion.util;

import com.sun.jna.Pointer;
import com.sun.jna.Platform;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsWindowActivatorTest {
    private static final Pointer WINDOW_HANDLE = new Pointer(123L);

    @Test
    void restoresBeforeActivatingTheNativeWindow() {
        List<String> events = new ArrayList<>();
        WindowsWindowActivator activator = new WindowsWindowActivator(new WindowsWindowActivator.NativeApi() {
            @Override
            public boolean setForegroundWindow(Pointer handle) {
                events.add("activate " + handle);
                return true;
            }
        });

        activator.activate(new WindowsWindowActivator.WindowControl() {
            @Override
            public void showAndRestore() {
                events.add("restore");
            }

            @Override
            public Pointer handle() {
                events.add("resolve handle");
                return WINDOW_HANDLE;
            }
        });

        assertEquals(List.of("restore", "resolve handle", "activate " + WINDOW_HANDLE), events);
    }

    @Test
    void reportsNativeActivationFailureExactly() {
        WindowsWindowActivator activator = new WindowsWindowActivator(new WindowsWindowActivator.NativeApi() {
            @Override
            public boolean setForegroundWindow(Pointer handle) {
                return false;
            }
        });

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> activator.activate(new WindowsWindowActivator.WindowControl() {
                    @Override
                    public void showAndRestore() {
                    }

                    @Override
                    public Pointer handle() {
                        return WINDOW_HANDLE;
                    }
                })
        );

        assertEquals("Windows refused to activate the Companion window", failure.getMessage());
    }

    @Test
    void windowsBindingResolvesTheNativeFunction() {
        assumeTrue(Platform.isWindows());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> WindowsWindowActivator.currentPlatform().activate(new WindowsWindowActivator.WindowControl() {
                    @Override
                    public void showAndRestore() {
                    }

                    @Override
                    public Pointer handle() {
                        return new Pointer(0L);
                    }
                })
        );

        assertEquals("Windows refused to activate the Companion window", failure.getMessage());
    }
}
