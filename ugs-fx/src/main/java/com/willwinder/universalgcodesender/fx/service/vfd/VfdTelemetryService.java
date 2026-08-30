/*
    Copyright 2026 Jon / CaptainKokomo
    GPL-3.0-or-later
 */
package com.willwinder.universalgcodesender.fx.service.vfd;

import com.fazecast.jSerialComm.SerialPort;
import com.willwinder.universalgcodesender.fx.settings.VfdSettings;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class VfdTelemetryService implements AutoCloseable {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, NO_RESPONSE }

    public record PortInfo(String systemName, String description) {
        @Override
        public String toString() {
            return description == null || description.isBlank() || description.equals(systemName)
                    ? systemName
                    : systemName + " — " + description;
        }
    }

    public interface Listener {
        default void onStateChanged(State state, String message) {
        }

        default void onSample(VfdTelemetrySnapshot snapshot) {
        }
    }

    private static final VfdTelemetryService INSTANCE = new VfdTelemetryService();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vfd-telemetry");
        thread.setDaemon(true);
        return thread;
    });

    private volatile ModbusRtuClient client;
    private volatile ScheduledFuture<?> pollTask;
    private volatile State state = State.DISCONNECTED;
    private volatile String portName = "";
    private volatile int slaveId = 1;

    private VfdTelemetryService() {
    }

    public static VfdTelemetryService getInstance() {
        return INSTANCE;
    }

    public static List<PortInfo> listPorts() {
        return Arrays.stream(SerialPort.getCommPorts())
                .map(port -> new PortInfo(port.getSystemPortName(), port.getDescriptivePortName()))
                .sorted((first, second) -> first.systemName().compareToIgnoreCase(second.systemName()))
                .toList();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onStateChanged(state, state == State.DISCONNECTED ? "Not connected" : "");
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized void connect(String selectedPort, int baud, int selectedSlaveId) {
        disconnect();
        portName = selectedPort;
        slaveId = selectedSlaveId;
        VfdSettings.setPort(selectedPort);
        VfdSettings.setBaud(baud);
        VfdSettings.setSlaveId(selectedSlaveId);
        publishState(State.CONNECTING, "Opening " + selectedPort);

        executor.execute(() -> {
            try {
                client = new ModbusRtuClient(selectedPort, baud);
                publishState(State.CONNECTED, selectedPort + " connected");
                pollTask = executor.scheduleWithFixedDelay(
                        this::pollOnce,
                        0,
                        VfdSettings.getPollIntervalMs(),
                        TimeUnit.MILLISECONDS);
            } catch (IOException exception) {
                publishState(State.DISCONNECTED, exception.getMessage());
                closeClient();
            }
        });
    }

    public synchronized void disconnect() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        closeClient();
        publishState(State.DISCONNECTED, "Not connected");
    }

    public State getState() {
        return state;
    }

    public String getPortName() {
        return portName;
    }

    public int getSlaveId() {
        return slaveId;
    }

    private void pollOnce() {
        ModbusRtuClient activeClient = client;
        if (activeClient == null) {
            return;
        }
        try {
            int[] registers = activeClient.readHoldingRegisters(
                    slaveId,
                    VfdTelemetrySnapshot.START_REGISTER,
                    VfdTelemetrySnapshot.REGISTER_COUNT);
            VfdTelemetrySnapshot snapshot = VfdTelemetrySnapshot.fromRegisters(registers);
            publishState(State.CONNECTED, portName + " · slave " + slaveId);
            listeners.forEach(listener -> listener.onSample(snapshot));
        } catch (IOException exception) {
            publishState(State.NO_RESPONSE, exception.getMessage());
        }
    }

    private void publishState(State newState, String message) {
        state = newState;
        listeners.forEach(listener -> listener.onStateChanged(newState, message));
    }

    private synchronized void closeClient() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Override
    public void close() {
        disconnect();
        executor.shutdownNow();
    }
}
