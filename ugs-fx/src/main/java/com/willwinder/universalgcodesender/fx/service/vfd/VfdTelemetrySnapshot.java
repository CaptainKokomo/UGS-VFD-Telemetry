/*
    Copyright 2026 Jon / CaptainKokomo
    GPL-3.0-or-later
 */
package com.willwinder.universalgcodesender.fx.service.vfd;

import java.time.Instant;

public record VfdTelemetrySnapshot(
        Instant timestamp,
        int[] rawRegisters,
        int primaryFault,
        int secondaryFault,
        int commandState,
        int runningStatus,
        double setFrequencyHz,
        double outputFrequencyHz,
        double outputCurrentA,
        double dcBusVoltageV,
        double outputVoltageV) {

    public static final int START_REGISTER = 0x2100;
    public static final int REGISTER_COUNT = 0x17;

    public static VfdTelemetrySnapshot fromRegisters(int[] registers) {
        if (registers.length != REGISTER_COUNT) {
            throw new IllegalArgumentException("Expected " + REGISTER_COUNT + " registers, got " + registers.length);
        }
        return new VfdTelemetrySnapshot(
                Instant.now(), registers.clone(), registers[0], registers[0x12], registers[1], registers[0x16],
                registers[2] * 0.01, registers[3] * 0.01, registers[4] * 0.1,
                registers[5] * 0.1, registers[6] * 0.1);
    }

    public boolean hasFault() {
        return primaryFault != 0 || secondaryFault != 0;
    }

    public int effectiveFault() {
        return primaryFault != 0 ? primaryFault : secondaryFault;
    }

    public String faultLabel() {
        return switch (effectiveFault()) {
            case 0 -> "No fault";
            case 1 -> "Module failure";
            case 2 -> "Overvoltage";
            case 3 -> "Temperature failure";
            case 4 -> "VFD overload";
            case 5 -> "Motor overload";
            case 6 -> "External fault";
            case 10 -> "Acceleration overcurrent";
            case 11 -> "Deceleration overcurrent";
            case 12 -> "Constant-speed overcurrent";
            case 14 -> "Undervoltage";
            default -> "Unknown fault " + effectiveFault();
        };
    }
}
