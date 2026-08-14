/*
 * VncWheelZoomBridge
 *
 * Experimental, opt-in bridge from the stock Kombi map magnification callback
 * to MHI2Q-VcMOSTRender. It is deliberately inert unless the Toolbox wheel
 * zoom test creates /tmp/mhi2q-vnc-wheel-enable.
 *
 * The source callback is ClusterService.onMagnificationChanged(int). This is
 * a downstream map-magnification signal, not a proven raw steering-wheel tick
 * API, so automatic/native map-scale changes may also reach this bridge.
 *
 * Java 1.2 compatible: no generics, no NIO, no autoboxing.
 */
package com.luka.carplay.routeguidance;

import java.io.File;
import java.io.FileOutputStream;

public final class VncWheelZoomBridge {
    private static final String ENABLE_FILE = "/tmp/mhi2q-vnc-wheel-enable";
    private static final String EVENT_FILE = "/tmp/mhi2q-vnc-wheel-event";
    private static final String LOG_FILE = "/tmp/mhi2q-vnc-wheel-java.log";

    private static boolean haveLast;
    private static int lastMagnification;
    private static long sequence;

    private VncWheelZoomBridge() {
    }

    public static synchronized void onMagnificationChanged(int magnification) {
        try {
            if (!new File(ENABLE_FILE).exists()) {
                haveLast = false;
                return;
            }

            int delta = haveLast ? magnification - lastMagnification : 0;
            lastMagnification = magnification;
            haveLast = true;
            sequence++;

            long now = System.currentTimeMillis();
            String line = String.valueOf(sequence) + " "
                    + String.valueOf(magnification) + " "
                    + String.valueOf(delta) + " "
                    + String.valueOf(now) + "\n";

            FileOutputStream event = null;
            try {
                event = new FileOutputStream(EVENT_FILE, false);
                event.write(line.getBytes());
                event.flush();
            } finally {
                if (event != null) {
                    try { event.close(); } catch (Throwable ignored) { }
                }
            }

            FileOutputStream log = null;
            try {
                log = new FileOutputStream(LOG_FILE, true);
                log.write(line.getBytes());
                log.flush();
            } finally {
                if (log != null) {
                    try { log.close(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) {
            /* Never allow the experiment bridge to affect stock navigation. */
        }
    }
}
