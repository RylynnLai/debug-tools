package com.debugtools.desktop;

import javax.swing.SwingUtilities;

public final class DesktopMain {
    private DesktopMain() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            DesktopFrame frame = new DesktopFrame();
            frame.setVisible(true);
        });
    }
}
