/*
    Copyright 2026 Jon / CaptainKokomo
    GPL-3.0-or-later
 */
package com.willwinder.universalgcodesender.fx.service.vfd;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.util.Arrays;

public final class ModbusRtuClient implements AutoCloseable {
    private static final int READ_HOLDING_REGISTERS = 0x03;
    private static final int TIMEOUT_MS = 850;

    private final SerialPort port;

    public ModbusRtuClient(String portName, int baud) throws IOException {
        port = SerialPort.getCommPort(portName);
        port.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                TIMEOUT_MS,
                TIMEOUT_MS);
        if (!port.openPort()) {
            throw new IOException("Could not open " + portName);
        }
    }

    public synchronized int[] readHoldingRegisters(int slaveId, int startAddress, int count) throws IOException {
        byte[] request = buildReadRequest(slaveId, startAddress, count);
        port.flushIOBuffers();
        int written = port.writeBytes(request, request.length);
        if (written != request.length) {
            throw new IOException("Serial write incomplete: " + written + "/" + request.length + " bytes");
        }

        byte[] header = readExact(3);
        int function = header[1] & 0xff;
        if (function == (READ_HOLDING_REGISTERS | 0x80)) {
            byte[] tail = readExact(2);
            byte[] frame = concat(header, tail);
            validateCrc(frame);
            throw new IOException("Modbus exception " + (header[2] & 0xff));
        }
        if (function != READ_HOLDING_REGISTERS) {
            throw new IOException(String.format("Unexpected Modbus function 0x%02X", function));
        }

        int byteCount = header[2] & 0xff;
        byte[] frame = concat(header, readExact(byteCount + 2));
        return parseReadResponse(frame, slaveId, count);
    }

    private byte[] readExact(int count) throws IOException {
        byte[] bytes = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = port.readBytes(bytes, count - offset, offset);
            if (read < 0) {
                throw new IOException("Serial port closed while reading");
            }
            if (read == 0) {
                throw new IOException("VFD did not reply");
            }
            offset += read;
        }
        return bytes;
    }

    static byte[] buildReadRequest(int slaveId, int address, int count) {
        if (slaveId < 1 || slaveId > 247) {
            throw new IllegalArgumentException("Slave ID must be 1 to 247");
        }
        if (address < 0 || address > 0xffff || count < 1 || count > 125 || address + count - 1 > 0xffff) {
            throw new IllegalArgumentException("Invalid register range");
        }

        byte[] body = new byte[] {
                (byte) slaveId,
                READ_HOLDING_REGISTERS,
                (byte) (address >>> 8),
                (byte) address,
                (byte) (count >>> 8),
                (byte) count
        };
        int crc = crc16(body, body.length);
        return concat(body, new byte[] {(byte) crc, (byte) (crc >>> 8)});
    }

    static int[] parseReadResponse(byte[] frame, int slaveId, int expectedCount) throws IOException {
        if (frame.length < 5) {
            throw new IOException("Short Modbus response");
        }
        validateCrc(frame);
        if ((frame[0] & 0xff) != slaveId) {
            throw new IOException("Reply from unexpected slave " + (frame[0] & 0xff));
        }
        if ((frame[1] & 0xff) != READ_HOLDING_REGISTERS) {
            throw new IOException("Unexpected Modbus response function");
        }
        int byteCount = frame[2] & 0xff;
        if (byteCount != expectedCount * 2 || frame.length != byteCount + 5) {
            throw new IOException("Unexpected Modbus payload length");
        }
        int[] values = new int[expectedCount];
        for (int index = 0; index < expectedCount; index++) {
            values[index] = ((frame[3 + index * 2] & 0xff) << 8) | (frame[4 + index * 2] & 0xff);
        }
        return values;
    }

    private static void validateCrc(byte[] frame) throws IOException {
        int received = (frame[frame.length - 2] & 0xff) | ((frame[frame.length - 1] & 0xff) << 8);
        int calculated = crc16(frame, frame.length - 2);
        if (received != calculated) {
            throw new IOException(String.format("Modbus CRC error (received %04X, calculated %04X)", received, calculated));
        }
    }

    static int crc16(byte[] data, int length) {
        int crc = 0xffff;
        for (int index = 0; index < length; index++) {
            crc ^= data[index] & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
            }
        }
        return crc & 0xffff;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    @Override
    public void close() {
        if (port.isOpen()) {
            port.closePort();
        }
    }
}
