package com.github.minecraft_ta.totalDebugCompanion.util;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.util.Objects;

final class WindowsWindowActivator {
    private final NativeApi nativeApi;

    WindowsWindowActivator(NativeApi nativeApi) {
        this.nativeApi = Objects.requireNonNull(nativeApi, "nativeApi");
    }

    static void activate(JFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Companion window activation must run on the Swing event thread");
        }
        SystemActivator.INSTANCE.activate(new SwingWindowControl(frame));
    }

    static WindowsWindowActivator currentPlatform() {
        User32 user32 = Native.load("user32", User32.class);
        return new WindowsWindowActivator(user32::SetForegroundWindow);
    }

    void activate(WindowControl window) {
        Objects.requireNonNull(window, "window");
        window.showAndRestore();
        Pointer handle = Objects.requireNonNull(window.handle(), "window handle");
        if (!this.nativeApi.setForegroundWindow(handle)) {
            throw new IllegalStateException("Windows refused to activate the Companion window");
        }
    }

    private static final class SystemActivator {
        private static final WindowsWindowActivator INSTANCE = currentPlatform();

        private SystemActivator() {
        }
    }

    interface NativeApi {
        boolean setForegroundWindow(Pointer handle);
    }

    interface WindowControl {
        void showAndRestore();

        Pointer handle();
    }

    private interface User32 extends StdCallLibrary {
        boolean SetForegroundWindow(Pointer windowHandle);
    }

    private record SwingWindowControl(JFrame frame) implements WindowControl {
        private SwingWindowControl {
            Objects.requireNonNull(frame, "frame");
        }

        @Override
        public void showAndRestore() {
            this.frame.setVisible(true);
            this.frame.setExtendedState(this.frame.getExtendedState() & ~JFrame.ICONIFIED);
        }

        @Override
        public Pointer handle() {
            return Native.getComponentPointer(this.frame);
        }
    }
}
