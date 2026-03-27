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
import java.util.Locale;

import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

final class WindowsMicaEffect {
    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_APPWINDOW = 0x00040000;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;
    private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private static final int DWMWA_SYSTEMBACKDROP_TYPE = 38;
    private static final int DWMWCP_ROUND = 2;
    private static final int DWMSBT_MAINWINDOW = 2;
    private static final Color TRANSPARENT = new Color(220, 220, 220, 0);
    private static final Color WINDOW_BACKGROUND = new Color(220, 220, 220, 1);

    private WindowsMicaEffect() {}

    static void prepare(Component component) {
        if (isUnsupportedPlatform())
            return;

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
        if (isUnsupportedPlatform())
            return;

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
        if (!dialog.isDisplayable())
            return;

        Linker linker = Linker.nativeLinker();
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
            SymbolLookup dwmapi = SymbolLookup.libraryLookup("dwmapi", arena);

            MethodHandle getForegroundWindow = linker.downcallHandle(
                user32.findOrThrow("GetForegroundWindow"),
                FunctionDescriptor.of(ValueLayout.ADDRESS)
            );
            MethodHandle findWindowW = linker.downcallHandle(
                user32.findOrThrow("FindWindowW"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MethodHandle getWindowLongW = linker.downcallHandle(
                user32.findOrThrow("GetWindowLongW"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            MethodHandle setWindowLongW = linker.downcallHandle(
                user32.findOrThrow("SetWindowLongW"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            MethodHandle setWindowPos = linker.downcallHandle(
                user32.findOrThrow("SetWindowPos"),
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
                dwmapi.findOrThrow("DwmSetWindowAttribute"),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT
                )
            );
            MethodHandle dwmExtendFrameIntoClientArea = linker.downcallHandle(
                dwmapi.findOrThrow("DwmExtendFrameIntoClientArea"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );

            MemorySegment hwnd = findDialogWindow(dialog, arena, findWindowW, getForegroundWindow);
            if (MemorySegment.NULL.equals(hwnd))
                return;

            promoteToTaskbarWindow(hwnd, getWindowLongW, setWindowLongW, setWindowPos);

            MemorySegment cornerPreference = arena.allocate(ValueLayout.JAVA_INT);
            cornerPreference.set(ValueLayout.JAVA_INT, 0, DWMWCP_ROUND);
            MemorySegment backdropType = arena.allocate(ValueLayout.JAVA_INT);
            backdropType.set(ValueLayout.JAVA_INT, 0, backdropTypeForPrimaryInstallerWindow());

            dwmSetWindowAttribute.invoke(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, cornerPreference, Integer.BYTES);
            int hresult = (int) dwmSetWindowAttribute.invoke(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, backdropType, Integer.BYTES);
            if (hresult != 0)
                return;

            MemorySegment margins = arena.allocate(4L * Integer.BYTES, Integer.BYTES);
            margins.set(ValueLayout.JAVA_INT, 0L, -1);
            margins.set(ValueLayout.JAVA_INT, Integer.BYTES, -1);
            margins.set(ValueLayout.JAVA_INT, 2L * Integer.BYTES, -1);
            margins.set(ValueLayout.JAVA_INT, 3L * Integer.BYTES, -1);
            dwmExtendFrameIntoClientArea.invoke(hwnd, margins);
            refreshWindowFrame(hwnd, setWindowPos);
        }
    }

    private static MemorySegment findDialogWindow(Dialog dialog, Arena arena, MethodHandle findWindowW, MethodHandle getForegroundWindow) throws Throwable {
        String title = dialog.getTitle();

        if (title != null && !title.isBlank()) {
            MemorySegment windowTitle = arena.allocateFrom(ValueLayout.JAVA_CHAR, (title + '\0').toCharArray());
            MemorySegment hwnd = (MemorySegment) findWindowW.invoke(MemorySegment.NULL, windowTitle);
            if (!MemorySegment.NULL.equals(hwnd)) {
                return hwnd;
            }
        }

        return (MemorySegment) getForegroundWindow.invoke();
    }


    private static void promoteToTaskbarWindow(
        MemorySegment hwnd,
        MethodHandle getWindowLongW,
        MethodHandle setWindowLongW,
        MethodHandle setWindowPos
    ) throws Throwable {
        int exStyle = (int) getWindowLongW.invoke(hwnd, GWL_EXSTYLE);
        int newExStyle = (exStyle | WS_EX_APPWINDOW) & ~WS_EX_TOOLWINDOW;
        if (newExStyle == exStyle)
            return;

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

