/*
    Copyright 2026 Jon / CaptainKokomo
    GPL-3.0-or-later
 */
package com.willwinder.universalgcodesender.fx.service.vfd;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ModbusRtuClientTest {
    @Test
    public void buildsLiteralHs350RegisterRequestWithLowCrcByteFirst() {
        byte[] request = ModbusRtuClient.buildReadRequest(1, 0x2102, 1);
        assertArrayEquals(hex("0103210200012ff6"), request);
    }

    @Test
    public void parsesHoldingRegisterResponse() throws IOException {
        int[] values = ModbusRtuClient.parseReadResponse(hex("010304006400c8ba7a"), 1, 2);
        assertArrayEquals(new int[] {100, 200}, values);
    }

    @Test
    public void crcMatchesModbusReferenceVector() {
        byte[] body = hex("010321000017");
        assertEquals(0xf80f, ModbusRtuClient.crc16(body, body.length));
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }
}
