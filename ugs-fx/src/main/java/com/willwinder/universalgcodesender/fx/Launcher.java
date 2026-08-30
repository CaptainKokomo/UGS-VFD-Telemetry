/*
    Copyright 2026 Joacim Breiler

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.willwinder.universalgcodesender.fx;

import com.willwinder.ugs.cli.TerminalClient;

import javax.swing.JOptionPane;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * This starter class is needed to start the application from the IDE
 */
public class Launcher {
    public static void main(String[] args) throws IOException {
        configurePortableHome();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> reportStartupFailure(error));

        try {
            if (args.length == 0 || !args[0].startsWith("-")) {
                Main.main(args);
                return;
            }

            TerminalClient.main(args);
        } catch (Throwable error) {
            reportStartupFailure(error);
        }
    }

    private static void configurePortableHome() throws IOException {
        if (!Boolean.getBoolean("ugs.portable")) {
            return;
        }

        String appPathProperty = System.getProperty("jpackage.app-path");
        Path applicationFolder = appPathProperty == null
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(appPathProperty).toAbsolutePath().getParent();
        Path dataFolder = applicationFolder.resolve("data");
        Files.createDirectories(dataFolder);
        System.setProperty("user.home", dataFolder.toString());
        System.setProperty("ugs.data.dir", dataFolder.toString());
    }

    private static void reportStartupFailure(Throwable error) {
        StringWriter stackTrace = new StringWriter();
        error.printStackTrace(new PrintWriter(stackTrace));

        Path logFile = Boolean.getBoolean("ugs.portable")
                ? Path.of(System.getProperty("ugs.data.dir", System.getProperty("user.home")), "startup-error.log")
                : Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")),
                        "UGS VFD Telemetry", "startup-error.log");
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile,
                    Instant.now() + System.lineSeparator() + stackTrace + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The dialog below still exposes the failure if the log cannot be written.
        }

        try {
            JOptionPane.showMessageDialog(null,
                    "UGS VFD Telemetry could not start.\n\n" +
                            error.getClass().getSimpleName() + ": " + error.getMessage() + "\n\n" +
                            "A detailed report was saved to:\n" + logFile,
                    "UGS VFD Telemetry startup error",
                    JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            // Nothing else can be displayed if the Windows UI itself is unavailable.
        }
    }
}
