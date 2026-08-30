/*
    Copyright 2026 Jon / CaptainKokomo
    GPL-3.0-or-later
 */
package com.willwinder.universalgcodesender.fx.service.vfd;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class VfdJobLogger implements AutoCloseable {
    private static final String APP_VERSION = "0.1.0";
    private static final String SCHEMA_VERSION = "1.0";
    private static final DateTimeFormatter FOLDER_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault());

    private BufferedWriter samplesWriter;
    private BufferedWriter eventsWriter;
    private Path jobFolder;
    private Instant started;
    private String port;
    private int slaveId;
    private long sampleCount;
    private long dropoutCount;
    private double maxCurrent;
    private double minBus = Double.POSITIVE_INFINITY;
    private double maxBus = Double.NEGATIVE_INFINITY;
    private double minFrequency = Double.POSITIVE_INFINITY;
    private double maxFrequency = Double.NEGATIVE_INFINITY;

    public synchronized Path start(String activePort, int activeSlaveId) throws IOException {
        stop();
        started = Instant.now();
        port = activePort;
        slaveId = activeSlaveId;
        sampleCount = 0;
        dropoutCount = 0;
        maxCurrent = 0;
        minBus = Double.POSITIVE_INFINITY;
        maxBus = Double.NEGATIVE_INFINITY;
        minFrequency = Double.POSITIVE_INFINITY;
        maxFrequency = Double.NEGATIVE_INFINITY;

        Path root = Path.of(System.getProperty("user.home"), "Documents", "UGS VFD Telemetry");
        jobFolder = root.resolve(FOLDER_FORMAT.format(started));
        Files.createDirectories(jobFolder);
        samplesWriter = Files.newBufferedWriter(jobFolder.resolve("samples.csv"), StandardCharsets.UTF_8);
        eventsWriter = Files.newBufferedWriter(jobFolder.resolve("events.jsonl"), StandardCharsets.UTF_8);
        writeCsvHeader();
        copyRegisterMap();
        event("job_started", "Job logging started", null);
        return jobFolder;
    }

    public synchronized boolean isActive() {
        return samplesWriter != null;
    }

    public synchronized Path getJobFolder() {
        return jobFolder;
    }

    public synchronized void sample(VfdTelemetrySnapshot snapshot) {
        if (!isActive()) {
            return;
        }
        try {
            double elapsed = Duration.between(started, snapshot.timestamp()).toMillis() / 1000.0;
            StringBuilder row = new StringBuilder();
            row.append(snapshot.timestamp()).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", elapsed)).append(',')
                    .append(csv(port)).append(',')
                    .append(slaveId).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", snapshot.setFrequencyHz())).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", snapshot.outputFrequencyHz())).append(',')
                    .append(String.format(Locale.ROOT, "%.1f", snapshot.outputCurrentA())).append(',')
                    .append(String.format(Locale.ROOT, "%.1f", snapshot.dcBusVoltageV())).append(',')
                    .append(String.format(Locale.ROOT, "%.1f", snapshot.outputVoltageV())).append(',')
                    .append(snapshot.primaryFault()).append(',')
                    .append(snapshot.secondaryFault()).append(',')
                    .append(snapshot.commandState()).append(',')
                    .append(snapshot.runningStatus()).append(',')
                    .append("ok").append(',')
                    .append(APP_VERSION).append(',')
                    .append(SCHEMA_VERSION);
            for (int raw : snapshot.rawRegisters()) {
                row.append(',').append(raw);
            }
            samplesWriter.write(row.toString());
            samplesWriter.newLine();
            samplesWriter.flush();

            sampleCount++;
            maxCurrent = Math.max(maxCurrent, snapshot.outputCurrentA());
            minBus = Math.min(minBus, snapshot.dcBusVoltageV());
            maxBus = Math.max(maxBus, snapshot.dcBusVoltageV());
            minFrequency = Math.min(minFrequency, snapshot.outputFrequencyHz());
            maxFrequency = Math.max(maxFrequency, snapshot.outputFrequencyHz());
        } catch (IOException ignored) {
            // The UI reports logger failures when starting/stopping; a transient write failure is
            // retained in the open writer and will surface again on close.
        }
    }

    public synchronized void dropout(String message) {
        if (!isActive()) {
            return;
        }
        dropoutCount++;
        event("communication_dropout", message, null);
    }

    public synchronized void event(String type, String message, VfdTelemetrySnapshot snapshot) {
        if (eventsWriter == null) {
            return;
        }
        try {
            String telemetry = snapshot == null ? "null" : String.format(Locale.ROOT,
                    "{\"frequency_hz\":%.2f,\"current_a\":%.1f,\"dc_bus_v\":%.1f,\"output_v\":%.1f,\"fault_raw\":%d}",
                    snapshot.outputFrequencyHz(), snapshot.outputCurrentA(), snapshot.dcBusVoltageV(),
                    snapshot.outputVoltageV(), snapshot.effectiveFault());
            eventsWriter.write("{\"timestamp\":\"" + Instant.now() + "\",\"type\":\"" + json(type)
                    + "\",\"message\":\"" + json(message) + "\",\"telemetry\":" + telemetry + "}");
            eventsWriter.newLine();
            eventsWriter.flush();
        } catch (IOException ignored) {
            // See sample(): close() will expose persistent writer failures.
        }
    }

    public synchronized void stop() throws IOException {
        if (samplesWriter == null && eventsWriter == null) {
            return;
        }
        event("job_stopped", "Job logging stopped", null);
        IOException failure = null;
        try {
            if (samplesWriter != null) {
                samplesWriter.close();
            }
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            if (eventsWriter != null) {
                eventsWriter.close();
            }
        } catch (IOException exception) {
            failure = exception;
        }
        samplesWriter = null;
        eventsWriter = null;
        writeSummary();
        if (failure != null) {
            throw failure;
        }
    }

    private void writeCsvHeader() throws IOException {
        StringBuilder header = new StringBuilder("timestamp,elapsed_seconds,com_port,slave_id,set_frequency_hz,output_frequency_hz,output_current_a,dc_bus_voltage_v,output_voltage_v,primary_fault_raw,secondary_fault_raw,command_state_raw,running_status_raw,poll_result,app_version,schema_version");
        for (int address = VfdTelemetrySnapshot.START_REGISTER;
             address < VfdTelemetrySnapshot.START_REGISTER + VfdTelemetrySnapshot.REGISTER_COUNT;
             address++) {
            header.append(String.format(",raw_%04X", address));
        }
        samplesWriter.write(header.toString());
        samplesWriter.newLine();
        samplesWriter.flush();
    }

    private void copyRegisterMap() throws IOException {
        try (InputStream input = VfdJobLogger.class.getResourceAsStream("/vfd/vfd_register_map.json")) {
            if (input != null) {
                Files.copy(input, jobFolder.resolve("vfd_register_map.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void writeSummary() throws IOException {
        if (jobFolder == null || started == null) {
            return;
        }
        double seconds = Duration.between(started, Instant.now()).toMillis() / 1000.0;
        String summary = String.format(Locale.ROOT,
                "{\n  \"schema_version\": \"%s\",\n  \"app_version\": \"%s\",\n  \"started\": \"%s\",\n  \"duration_seconds\": %.3f,\n  \"com_port\": \"%s\",\n  \"slave_id\": %d,\n  \"samples\": %d,\n  \"communication_dropouts\": %d,\n  \"max_current_a\": %.1f,\n  \"min_dc_bus_v\": %.1f,\n  \"max_dc_bus_v\": %.1f,\n  \"min_frequency_hz\": %.2f,\n  \"max_frequency_hz\": %.2f\n}\n",
                SCHEMA_VERSION, APP_VERSION, started, seconds, json(port), slaveId, sampleCount, dropoutCount,
                maxCurrent, finiteOrZero(minBus), finiteOrZero(maxBus), finiteOrZero(minFrequency), finiteOrZero(maxFrequency));
        Files.writeString(jobFolder.resolve("summary.json"), summary, StandardCharsets.UTF_8);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0;
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    @Override
    public void close() throws IOException {
        stop();
    }
}
