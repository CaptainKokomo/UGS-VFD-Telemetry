/*
    Copyright 2026 Jon / CaptainKokomo
    GPL-3.0-or-later
 */
package com.willwinder.universalgcodesender.fx.component.vfd;

import com.willwinder.universalgcodesender.fx.service.vfd.VfdJobLogger;
import com.willwinder.universalgcodesender.fx.service.vfd.VfdTelemetryService;
import com.willwinder.universalgcodesender.fx.service.vfd.VfdTelemetrySnapshot;
import com.willwinder.universalgcodesender.fx.settings.VfdSettings;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.events.StreamEvent;
import com.willwinder.universalgcodesender.model.events.StreamEventType;
import com.willwinder.universalgcodesender.services.LookupService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

public class VfdTelemetryPane extends BorderPane {
    private enum Metric {
        CURRENT("Output current", "A"),
        FREQUENCY("Output frequency", "Hz"),
        DC_BUS("DC bus", "V");

        private final String label;
        private final String unit;

        Metric(String label, String unit) {
            this.label = label;
            this.unit = unit;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private record HistoryPoint(double elapsed, VfdTelemetrySnapshot snapshot) {
    }

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final int MAX_HISTORY_POINTS = 7200;

    private final VfdTelemetryService service = VfdTelemetryService.getInstance();
    private final VfdJobLogger logger = new VfdJobLogger();
    private final ComboBox<VfdTelemetryService.PortInfo> portBox = new ComboBox<>();
    private final ComboBox<Integer> baudBox = new ComboBox<>();
    private final Spinner<Integer> slaveSpinner = new Spinner<>(1, 247, VfdSettings.getSlaveId());
    private final Button connectButton = new Button("Connect");
    private final Label stateLabel = new Label("VFD OFFLINE");
    private final Label detailLabel = new Label("Not connected");
    private final Label frequencyValue = tileValue();
    private final Label currentValue = tileValue();
    private final Label busValue = tileValue();
    private final Label voltageValue = tileValue();
    private final Label faultValue = tileValue();
    private final Label loggingLabel = new Label("Not logging");
    private final Button startLogButton = new Button("Start Job Log");
    private final Button stopLogButton = new Button("Stop Job Log");
    private final Button openFolderButton = new Button("Open Log Folder");
    private final ComboBox<Metric> metricBox = new ComboBox<>();
    private final XYChart.Series<Number, Number> chartSeries = new XYChart.Series<>();
    private final NumberAxis xAxis = new NumberAxis();
    private final NumberAxis yAxis = new NumberAxis();
    private final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
    private final ListView<String> faultTimeline = new ListView<>();
    private final Deque<HistoryPoint> history = new ArrayDeque<>();
    private Instant historyStarted;
    private VfdTelemetrySnapshot latestSnapshot;
    private int previousFault;
    private VfdTelemetryService.State previousState = VfdTelemetryService.State.DISCONNECTED;

    public VfdTelemetryPane() {
        getStyleClass().add("vfd-pane");
        getStylesheets().add(Objects.requireNonNull(getClass().getResource("/styles/vfd-telemetry.css")).toExternalForm());
        setMinWidth(330);
        setPrefWidth(410);

        VBox content = new VBox(12,
                createHeader(),
                createConnectionControls(),
                createTiles(),
                createTrendSection(),
                createFaultSection(),
                createLoggingControls());
        content.setPadding(new Insets(14));

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("vfd-scroll");
        setCenter(scrollPane);

        initialiseControls();
        registerListeners();
        registerJobEvents();
        Platform.runLater(this::refreshPorts);
    }

    private VBox createHeader() {
        Label title = new Label("VFD TELEMETRY");
        title.getStyleClass().add("vfd-title");
        stateLabel.getStyleClass().addAll("vfd-state", "vfd-state-offline");
        detailLabel.getStyleClass().add("vfd-detail");
        detailLabel.setWrapText(true);

        HBox statusRow = new HBox(10, title, spacer(), stateLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        return new VBox(4, statusRow, detailLabel);
    }

    private VBox createConnectionControls() {
        portBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(portBox, Priority.ALWAYS);
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(event -> refreshPorts());
        HBox portRow = new HBox(8, portBox, refreshButton);

        baudBox.setPrefWidth(105);
        slaveSpinner.setPrefWidth(85);
        connectButton.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(connectButton, Priority.ALWAYS);
        HBox settingsRow = new HBox(8,
                labeled("Baud", baudBox),
                labeled("Slave", slaveSpinner),
                connectButton);
        settingsRow.setAlignment(Pos.BOTTOM_LEFT);
        return new VBox(7, portRow, settingsRow);
    }

    private GridPane createTiles() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(tile("OUTPUT FREQUENCY", frequencyValue, "Hz"), 0, 0);
        grid.add(tile("OUTPUT CURRENT", currentValue, "A"), 1, 0);
        grid.add(tile("DC BUS", busValue, "V"), 0, 1);
        grid.add(tile("OUTPUT VOLTAGE", voltageValue, "V"), 1, 1);
        grid.add(tile("LATEST FAULT", faultValue, ""), 0, 2, 2, 1);
        grid.getColumnConstraints().addAll(
                new javafx.scene.layout.ColumnConstraints(150, 200, Double.MAX_VALUE, Priority.ALWAYS, null, true),
                new javafx.scene.layout.ColumnConstraints(150, 200, Double.MAX_VALUE, Priority.ALWAYS, null, true));
        return grid;
    }

    private VBox createTrendSection() {
        metricBox.setItems(FXCollections.observableArrayList(Metric.values()));
        metricBox.setValue(Metric.CURRENT);
        metricBox.valueProperty().addListener((observable, oldValue, newValue) -> rebuildChart());

        xAxis.setLabel("Job elapsed (minutes)");
        xAxis.setForceZeroInRange(false);
        yAxis.setLabel(Metric.CURRENT.unit);
        yAxis.setForceZeroInRange(false);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.setMinHeight(210);
        chart.getData().add(chartSeries);
        VBox.setVgrow(chart, Priority.ALWAYS);

        HBox heading = new HBox(8, sectionTitle("JOB TREND"), spacer(), metricBox);
        heading.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, heading, chart);
    }

    private VBox createFaultSection() {
        faultTimeline.setPlaceholder(new Label("No faults recorded"));
        faultTimeline.setPrefHeight(110);
        return new VBox(6, sectionTitle("FAULT TIMELINE"), faultTimeline);
    }

    private VBox createLoggingControls() {
        stopLogButton.setDisable(true);
        openFolderButton.setDisable(true);
        startLogButton.setOnAction(event -> startLogging(false));
        stopLogButton.setOnAction(event -> stopLogging());
        openFolderButton.setOnAction(event -> openLogFolder());

        Button noteButton = new Button("Add Note");
        noteButton.setOnAction(event -> addNote());
        Button toolChangeButton = new Button("Mark Tool Change");
        toolChangeButton.setOnAction(event -> logger.event("tool_change", "Tool change", latestSnapshot));
        HBox rowOne = new HBox(8, startLogButton, stopLogButton);
        HBox rowTwo = new HBox(8, noteButton, toolChangeButton, openFolderButton);
        return new VBox(7, sectionTitle("JOB LOG"), loggingLabel, rowOne, rowTwo);
    }

    private void initialiseControls() {
        baudBox.setItems(FXCollections.observableArrayList(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200));
        baudBox.setValue(VfdSettings.getBaud());
        connectButton.setOnAction(event -> {
            if (service.getState() == VfdTelemetryService.State.DISCONNECTED) {
                connect();
            } else {
                service.disconnect();
            }
        });
    }

    private void registerListeners() {
        service.addListener(new VfdTelemetryService.Listener() {
            @Override
            public void onStateChanged(VfdTelemetryService.State state, String message) {
                Platform.runLater(() -> updateState(state, message));
            }

            @Override
            public void onSample(VfdTelemetrySnapshot snapshot) {
                logger.sample(snapshot);
                Platform.runLater(() -> updateTelemetry(snapshot));
            }
        });
    }

    private void registerJobEvents() {
        BackendAPI backend = LookupService.lookup(BackendAPI.class);
        backend.addUGSEventListener(event -> {
            if (!(event instanceof StreamEvent streamEvent)) {
                return;
            }
            if (streamEvent.getType() == StreamEventType.STREAM_STARTED) {
                Platform.runLater(() -> startLogging(true));
            } else if (streamEvent.getType() == StreamEventType.STREAM_COMPLETE
                    || streamEvent.getType() == StreamEventType.STREAM_CANCELED) {
                Platform.runLater(this::stopLogging);
            }
        });
    }

    private void refreshPorts() {
        String selectedName = portBox.getValue() == null ? VfdSettings.getPort() : portBox.getValue().systemName();
        BackendAPI backend = LookupService.lookup(BackendAPI.class);
        String cncPort = backend.getSettings().getPort();
        if (selectedName.equalsIgnoreCase(cncPort)) {
            selectedName = "";
            VfdSettings.setPort("");
        }
        String desiredPort = selectedName;
        List<VfdTelemetryService.PortInfo> ports = VfdTelemetryService.listPorts();
        portBox.setItems(FXCollections.observableArrayList(ports));
        ports.stream()
                .filter(port -> port.systemName().equalsIgnoreCase(desiredPort))
                .findFirst()
                .ifPresent(portBox::setValue);

        if (ports.isEmpty()) {
            detailLabel.setText("No USB serial adaptor detected");
        } else if (ports.size() == 1 && ports.get(0).systemName().equalsIgnoreCase(cncPort)) {
            portBox.setValue(null);
            detailLabel.setText(cncPort + " is the CNC controller. USB-RS485 adaptor not detected.");
        }
    }

    private void connect() {
        VfdTelemetryService.PortInfo selected = portBox.getValue();
        if (selected == null) {
            showError("No serial port selected", "Plug in the USB-to-RS485 adaptor and press Refresh.");
            return;
        }
        BackendAPI backend = LookupService.lookup(BackendAPI.class);
        if (selected.systemName().equalsIgnoreCase(backend.getSettings().getPort())) {
            showError(selected.systemName() + " is assigned to the CNC controller",
                    "Select the separate USB-to-RS485 adaptor port.");
            return;
        }
        service.connect(selected.systemName(), baudBox.getValue(), slaveSpinner.getValue());
    }

    private void updateState(VfdTelemetryService.State state, String message) {
        stateLabel.getStyleClass().removeAll(
                "vfd-state-offline", "vfd-state-connecting", "vfd-state-online", "vfd-state-error");
        switch (state) {
            case CONNECTED -> {
                stateLabel.setText("ONLINE");
                stateLabel.getStyleClass().add("vfd-state-online");
                connectButton.setText("Disconnect");
            }
            case CONNECTING -> {
                stateLabel.setText("CONNECTING");
                stateLabel.getStyleClass().add("vfd-state-connecting");
                connectButton.setText("Disconnect");
            }
            case NO_RESPONSE -> {
                stateLabel.setText("NO RESPONSE");
                stateLabel.getStyleClass().add("vfd-state-error");
                connectButton.setText("Disconnect");
                if (previousState != VfdTelemetryService.State.NO_RESPONSE) {
                    logger.dropout(message);
                }
            }
            case DISCONNECTED -> {
                stateLabel.setText("OFFLINE");
                stateLabel.getStyleClass().add("vfd-state-offline");
                connectButton.setText("Connect");
            }
        }
        detailLabel.setText(message == null ? "" : message);
        previousState = state;
    }

    private void updateTelemetry(VfdTelemetrySnapshot snapshot) {
        latestSnapshot = snapshot;
        frequencyValue.setText(String.format("%.2f", snapshot.outputFrequencyHz()));
        currentValue.setText(String.format("%.1f", snapshot.outputCurrentA()));
        busValue.setText(String.format("%.1f", snapshot.dcBusVoltageV()));
        voltageValue.setText(String.format("%.1f", snapshot.outputVoltageV()));
        faultValue.setText(snapshot.faultLabel());
        faultValue.getStyleClass().removeAll("vfd-value-ok", "vfd-value-fault");
        faultValue.getStyleClass().add(snapshot.hasFault() ? "vfd-value-fault" : "vfd-value-ok");

        if (snapshot.effectiveFault() != previousFault) {
            if (snapshot.hasFault()) {
                String event = TIME_FORMAT.format(snapshot.timestamp()) + "  " + snapshot.faultLabel()
                        + String.format("  |  %.1f A  %.2f Hz  %.1f VDC",
                        snapshot.outputCurrentA(), snapshot.outputFrequencyHz(), snapshot.dcBusVoltageV());
                faultTimeline.getItems().add(0, event);
                logger.event("vfd_fault", snapshot.faultLabel(), snapshot);
            } else if (previousFault != 0) {
                faultTimeline.getItems().add(0, TIME_FORMAT.format(snapshot.timestamp()) + "  Fault cleared");
                logger.event("vfd_fault_cleared", "Fault cleared", snapshot);
            }
            previousFault = snapshot.effectiveFault();
        }

        addHistory(snapshot);
    }

    private void addHistory(VfdTelemetrySnapshot snapshot) {
        if (historyStarted == null) {
            historyStarted = snapshot.timestamp();
        }
        double elapsedMinutes = Duration.between(historyStarted, snapshot.timestamp()).toMillis() / 60000.0;
        HistoryPoint point = new HistoryPoint(elapsedMinutes, snapshot);
        history.addLast(point);
        while (history.size() > MAX_HISTORY_POINTS) {
            history.removeFirst();
        }
        chartSeries.getData().add(new XYChart.Data<>(elapsedMinutes, metricValue(metricBox.getValue(), snapshot)));
        while (chartSeries.getData().size() > MAX_HISTORY_POINTS) {
            chartSeries.getData().remove(0);
        }
    }

    private void rebuildChart() {
        Metric metric = metricBox.getValue();
        if (metric == null) {
            return;
        }
        yAxis.setLabel(metric.unit);
        chartSeries.getData().clear();
        for (HistoryPoint point : history) {
            chartSeries.getData().add(new XYChart.Data<>(point.elapsed(), metricValue(metric, point.snapshot())));
        }
    }

    private static double metricValue(Metric metric, VfdTelemetrySnapshot snapshot) {
        return switch (metric) {
            case CURRENT -> snapshot.outputCurrentA();
            case FREQUENCY -> snapshot.outputFrequencyHz();
            case DC_BUS -> snapshot.dcBusVoltageV();
        };
    }

    private void startLogging(boolean automatic) {
        if (logger.isActive()) {
            return;
        }
        try {
            Path folder = logger.start(service.getPortName(), service.getSlaveId());
            loggingLabel.setText((automatic ? "Automatic job log: " : "Logging: ") + folder.getFileName());
            startLogButton.setDisable(true);
            stopLogButton.setDisable(false);
            openFolderButton.setDisable(false);
            history.clear();
            chartSeries.getData().clear();
            historyStarted = null;
        } catch (IOException exception) {
            showError("Could not start job log", exception.getMessage());
        }
    }

    private void stopLogging() {
        if (!logger.isActive()) {
            return;
        }
        try {
            logger.stop();
            loggingLabel.setText("Job log complete");
            startLogButton.setDisable(false);
            stopLogButton.setDisable(true);
            openFolderButton.setDisable(logger.getJobFolder() == null);
        } catch (IOException exception) {
            showError("Could not finish job log", exception.getMessage());
        }
    }

    private void addNote() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Add Job Note");
        dialog.setHeaderText("Add a timestamped note to this job");
        dialog.setContentText("Note:");
        dialog.showAndWait()
                .filter(text -> !text.isBlank())
                .ifPresent(text -> logger.event("note", text, latestSnapshot));
    }

    private void openLogFolder() {
        Path folder = logger.getJobFolder();
        if (folder == null) {
            return;
        }
        try {
            Desktop.getDesktop().open(folder.toFile());
        } catch (IOException | UnsupportedOperationException exception) {
            showError("Could not open log folder", folder.toString());
        }
    }

    private static VBox tile(String title, Label value, String unit) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("vfd-tile-title");
        Label unitLabel = new Label(unit);
        unitLabel.getStyleClass().add("vfd-tile-unit");
        HBox valueRow = new HBox(5, value, unitLabel);
        valueRow.setAlignment(Pos.BASELINE_LEFT);
        VBox tile = new VBox(4, titleLabel, valueRow);
        tile.getStyleClass().add("vfd-tile");
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
    }

    private static Label tileValue() {
        Label label = new Label("—");
        label.getStyleClass().add("vfd-tile-value");
        return label;
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("vfd-section-title");
        return label;
    }

    private static VBox labeled(String text, javafx.scene.Node node) {
        Label label = new Label(text);
        label.getStyleClass().add("vfd-field-label");
        return new VBox(2, label, node);
    }

    private static javafx.scene.layout.Region spacer() {
        javafx.scene.layout.Region region = new javafx.scene.layout.Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static void showError(String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("UGS VFD Telemetry");
        alert.setHeaderText(header);
        alert.setContentText(content == null ? "Unknown error" : content);
        alert.show();
    }

    public void shutdown() {
        try {
            logger.stop();
        } catch (IOException ignored) {
            // Application shutdown continues; samples are flushed on every write.
        }
    }
}
