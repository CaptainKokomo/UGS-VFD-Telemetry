/*
    Copyright 2026 Jon / CaptainKokomo
    GPL-3.0-or-later
 */
package com.willwinder.universalgcodesender.fx.service.vfd;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VfdTelemetrySnapshotTest {
    @Test
    public void decodesHs350TelemetryScaling() {
        int[] raw = new int[VfdTelemetrySnapshot.REGISTER_COUNT];
        raw[0] = 10;
        raw[2] = 32900;
        raw[3] = 32890;
        raw[4] = 135;
        raw[5] = 3260;
        raw[6] = 2200;

        VfdTelemetrySnapshot snapshot = VfdTelemetrySnapshot.fromRegisters(raw);
        assertEquals(329.0, snapshot.setFrequencyHz(), 0.001);
        assertEquals(328.9, snapshot.outputFrequencyHz(), 0.001);
        assertEquals(13.5, snapshot.outputCurrentA(), 0.001);
        assertEquals(326.0, snapshot.dcBusVoltageV(), 0.001);
        assertEquals("Acceleration overcurrent", snapshot.faultLabel());
    }
}
