/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.installer;

import java.awt.*;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.Locale;

import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

final class WindowsMicaEffect {
    private static final int FALSE = 0;
    private static final int TRUE = 1;
    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_APPWINDOW = 0x00040000;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private static final int DWMWA_SYSTEMBACKDROP_TYPE = 38;
    private static final int DWMWCP_ROUND = 2;
    private static final int DWMSBT_MAINWINDOW = 2;
    private static final Path SYSTEM32 = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "System32");
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);
    private static final Color WINDOW_BACKGROUND = new Color(220, 220, 220, 1);
    private static final MethodHandle ENUM_WINDOWS_CALLBACK;

    private static volatile MethodHandle enumGetWindowThreadProcessId;
    private static volatile MethodHandle enumIsWindowVisible;
    private static volatile MethodHandle enumGetWindowTextLengthW;
    private static volatile MethodHandle enumGetWindowTextW;
    private static volatile int enumTargetProcessId;
    private static volatile String enumTargetWindowTitle;
    private static volatile MemorySegment enumFoundWindow;
    private static volatile Throwable enumCallbackFailure;

    static {
        try {
            ENUM_WINDOWS_CALLBACK = MethodHandles.lookup().findStatic(
                WindowsMicaEffect.class,
                "enumWindowsProc",
                MethodType.methodType(int.class, MemorySegment.class, long.class)
            );
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private WindowsMicaEffect() {}

    static void prepare(Component component) {
        if (isUnsupportedPlatform()) {
            return;
        }

        if (component instanceof JPanel || component instanceof JOptionPane || component instanceof JRadioButton) {
            JComponent jComponent = (JComponent) component;
            jComponent.setOpaque(false);
            jComponent.setBackground(TRANSPARENT);
        }

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                prepare(child);
            }
        }
    }

    static void install(JDialog dialog) {
        if (isUnsupportedPlatform()) {
            return;
        }

        dialog.getRootPane().setOpaque(false);
        dialog.getLayeredPane().setOpaque(false);
        if (dialog.getContentPane() instanceof JComponent contentPane) {
            contentPane.setOpaque(false);
            contentPane.setBackground(TRANSPARENT);
        }
        prepare(dialog.getRootPane());

        if (dialog.isUndecorated()) {
            try {
                dialog.setBackground(WINDOW_BACKGROUND);
            } catch (UnsupportedOperationException | IllegalComponentStateException ignored) {
                // Some window configurations do not allow a translucent background.
            }
        }

        Runnable apply = () -> {
            try {
                applyTo(dialog);
                dialog.invalidate();
                dialog.validate();
                dialog.repaint();
                dialog.getRootPane().repaint();
            } catch (Throwable ignored) {
                // Leave the standard dialog untouched if Windows backdrop APIs are unavailable.
            }
        };

        if (dialog.isShowing()) {
            apply.run();
            return;
        }

        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                dialog.removeWindowListener(this);
                apply.run();
            }
        });
    }

    private static void applyTo(Dialog dialog) throws Throwable {
        if (!dialog.isDisplayable()) {
            return;
        }

        Linker linker = Linker.nativeLinker();
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup user32 = SymbolLookup.libraryLookup(SYSTEM32.resolve("user32.dll"), arena);
            SymbolLookup kernel32 = SymbolLookup.libraryLookup(SYSTEM32.resolve("kernel32.dll"), arena);
            SymbolLookup dwmapi = SymbolLookup.libraryLookup(SYSTEM32.resolve("dwmapi.dll"), arena);

            MethodHandle enumWindows = linker.downcallHandle(
                user32.find("EnumWindows").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
            );
            MethodHandle getCurrentProcessId = linker.downcallHandle(
                kernel32.find("GetCurrentProcessId").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT)
            );
            MethodHandle getWindowThreadProcessId = linker.downcallHandle(
                user32.find("GetWindowThreadProcessId").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MethodHandle isWindowVisible = linker.downcallHandle(
                user32.find("IsWindowVisible").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            );
            MethodHandle getWindowTextLengthW = linker.downcallHandle(
                user32.find("GetWindowTextLengthW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            );
            MethodHandle getWindowTextW = linker.downcallHandle(
                user32.find("GetWindowTextW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            MethodHandle getForegroundWindow = linker.downcallHandle(
                user32.find("GetForegroundWindow").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS)
            );
            MethodHandle getWindowLongW = linker.downcallHandle(
                user32.find("GetWindowLongW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            MethodHandle setWindowLongW = linker.downcallHandle(
                user32.find("SetWindowLongW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            MethodHandle setWindowPos = linker.downcallHandle(
                user32.find("SetWindowPos").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT
                )
            );
            MethodHandle dwmSetWindowAttribute = linker.downcallHandle(
                dwmapi.find("DwmSetWindowAttribute").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT
                )
            );
            MethodHandle dwmExtendFrameIntoClientArea = linker.downcallHandle(
                dwmapi.find("DwmExtendFrameIntoClientArea").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );

            MemorySegment hwnd = findDialogWindow(
                dialog,
                arena,
                linker,
                enumWindows,
                getCurrentProcessId,
                getWindowThreadProcessId,
                isWindowVisible,
                getWindowTextLengthW,
                getWindowTextW,
                getForegroundWindow
            );
            if (MemorySegment.NULL.equals(hwnd)) {
                return;
            }

            promoteToTaskbarWindow(hwnd, getWindowLongW, setWindowLongW, setWindowPos);

            MemorySegment cornerPreference = arena.allocate(ValueLayout.JAVA_INT);
            cornerPreference.set(ValueLayout.JAVA_INT, 0, DWMWCP_ROUND);
            MemorySegment lightMode = arena.allocate(ValueLayout.JAVA_INT);
            lightMode.set(ValueLayout.JAVA_INT, 0, FALSE);
            MemorySegment backdropType = arena.allocate(ValueLayout.JAVA_INT);
            backdropType.set(ValueLayout.JAVA_INT, 0, backdropTypeForPrimaryInstallerWindow());

            dwmSetWindowAttribute.invoke(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, lightMode, Integer.BYTES);
            dwmSetWindowAttribute.invoke(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, cornerPreference, Integer.BYTES);
            int hresult = (int) dwmSetWindowAttribute.invoke(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, backdropType, Integer.BYTES);
            if (hresult != 0) {
                return;
            }

            MemorySegment margins = arena.allocate(4L * Integer.BYTES, Integer.BYTES);
            margins.set(ValueLayout.JAVA_INT, 0L, -1);
            margins.set(ValueLayout.JAVA_INT, Integer.BYTES, -1);
            margins.set(ValueLayout.JAVA_INT, 2L * Integer.BYTES, -1);
            margins.set(ValueLayout.JAVA_INT, 3L * Integer.BYTES, -1);
            dwmExtendFrameIntoClientArea.invoke(hwnd, margins);
            refreshWindowFrame(hwnd, setWindowPos);
        }
    }

    private static MemorySegment findDialogWindow(
        Dialog dialog,
        Arena arena,
        Linker linker,
        MethodHandle enumWindows,
        MethodHandle getCurrentProcessId,
        MethodHandle getWindowThreadProcessId,
        MethodHandle isWindowVisible,
        MethodHandle getWindowTextLengthW,
        MethodHandle getWindowTextW,
        MethodHandle getForegroundWindow
    ) throws Throwable {
        String title = dialog.getTitle();

        enumTargetProcessId = (int) getCurrentProcessId.invoke();
        enumTargetWindowTitle = title;
        enumGetWindowThreadProcessId = getWindowThreadProcessId;
        enumIsWindowVisible = isWindowVisible;
        enumGetWindowTextLengthW = getWindowTextLengthW;
        enumGetWindowTextW = getWindowTextW;
        enumFoundWindow = MemorySegment.NULL;
        enumCallbackFailure = null;

        try {
            MemorySegment callback = linker.upcallStub(
                ENUM_WINDOWS_CALLBACK,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                arena
            );
            enumWindows.invoke(callback, 0L);
            if (enumCallbackFailure != null) {
                throw enumCallbackFailure;
            }
            if (!MemorySegment.NULL.equals(enumFoundWindow)) {
                return enumFoundWindow;
            }
        } finally {
            enumGetWindowThreadProcessId = null;
            enumIsWindowVisible = null;
            enumGetWindowTextLengthW = null;
            enumGetWindowTextW = null;
            enumTargetWindowTitle = null;
            enumCallbackFailure = null;
            enumFoundWindow = MemorySegment.NULL;
            enumTargetProcessId = 0;
        }

        return (MemorySegment) getForegroundWindow.invoke();
    }

    private static int enumWindowsProc(MemorySegment hwnd, long ignoredLParam) {
        try (Arena arena = Arena.ofConfined()) {
            if ((int) enumIsWindowVisible.invoke(hwnd) == FALSE) {
                return TRUE;
            }

            MemorySegment processId = arena.allocate(ValueLayout.JAVA_INT);
            enumGetWindowThreadProcessId.invoke(hwnd, processId);
            if (processId.get(ValueLayout.JAVA_INT, 0) != enumTargetProcessId) {
                return TRUE;
            }

            if (enumTargetWindowTitle != null && !enumTargetWindowTitle.isBlank()) {
                int titleLength = (int) enumGetWindowTextLengthW.invoke(hwnd);
                if (titleLength <= 0) {
                    return TRUE;
                }

                MemorySegment titleBuffer = arena.allocate(
                    (titleLength + 1L) * ValueLayout.JAVA_CHAR.byteSize(),
                    ValueLayout.JAVA_CHAR.byteAlignment()
                );
                enumGetWindowTextW.invoke(hwnd, titleBuffer, titleLength + 1);
                if (!enumTargetWindowTitle.equals(readUtf16String(titleBuffer))) {
                    return TRUE;
                }
            }

            enumFoundWindow = hwnd;
            return FALSE;
        } catch (Throwable throwable) {
            enumCallbackFailure = throwable;
            return FALSE;
        }
    }

    private static String readUtf16String(MemorySegment buffer) {
        char[] chars = buffer.toArray(ValueLayout.JAVA_CHAR);
        int length = 0;
        while (length < chars.length && chars[length] != '\0') {
            length++;
        }
        return new String(chars, 0, length);
    }

    private static void promoteToTaskbarWindow(
        MemorySegment hwnd,
        MethodHandle getWindowLongW,
        MethodHandle setWindowLongW,
        MethodHandle setWindowPos
    ) throws Throwable {
        int exStyle = (int) getWindowLongW.invoke(hwnd, GWL_EXSTYLE);
        int newExStyle = (exStyle | WS_EX_APPWINDOW) & ~WS_EX_TOOLWINDOW;
        if (newExStyle == exStyle) {
            return;
        }

        setWindowLongW.invoke(hwnd, GWL_EXSTYLE, newExStyle);
        refreshWindowFrame(hwnd, setWindowPos);
    }

    private static void refreshWindowFrame(MemorySegment hwnd, MethodHandle setWindowPos) throws Throwable {
        setWindowPos.invoke(
            hwnd,
            MemorySegment.NULL,
            0,
            0,
            0,
            0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED
        );
    }

    static boolean isSupported() {
        return !isUnsupportedPlatform();
    }

    private static boolean isUnsupportedPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        if (!osName.contains("windows")) {
            return true;
        }
        if (osName.contains("windows 11")) {
            return false;
        }

        String osVersion = System.getProperty("os.version", "");
        String[] parts = osVersion.split("\\.");
        if (parts.length < 3) {
            return true;
        }

        try {
            return Integer.parseInt(parts[2]) < 22000;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    private static int backdropTypeForPrimaryInstallerWindow() {
        return DWMSBT_MAINWINDOW;
    }
}

