/*
    Copyright 2026 Jon / CaptainKokomo

    This file is part of Universal Gcode Sender (UGS).
    UGS is free software under the GNU General Public License version 3.
 */
package com.willwinder.universalgcodesender.fx.settings;

import java.util.prefs.Preferences;

public final class VfdSettings {
    private static final Preferences PREFS = Preferences.userNodeForPackage(VfdSettings.class);
    private static final String PORT = "vfd.port";
    private static final String BAUD = "vfd.baud";
    private static final String SLAVE_ID = "vfd.slaveId";
    private static final String POLL_INTERVAL = "vfd.pollIntervalMs";

    private VfdSettings() {
    }

    public static String getPort() {
        return PREFS.get(PORT, "");
    }

    public static void setPort(String value) {
        PREFS.put(PORT, value == null ? "" : value);
    }

    public static int getBaud() {
        return PREFS.getInt(BAUD, 9600);
    }

    public static void setBaud(int value) {
        PREFS.putInt(BAUD, value);
    }

    public static int getSlaveId() {
        return PREFS.getInt(SLAVE_ID, 1);
    }

    public static void setSlaveId(int value) {
        PREFS.putInt(SLAVE_ID, value);
    }

    public static int getPollIntervalMs() {
        return PREFS.getInt(POLL_INTERVAL, 500);
    }
}
