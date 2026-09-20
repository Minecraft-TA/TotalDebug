package com.github.minecraft_ta.totalDebugCompanion;

import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CompanionRenderingDefaultsTest {
    @Test void startupSetsGrayscaleAntialiasingBeforeAwtAndPreservesExplicitOverrides() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (String option : List.of("default", "off", "lcd")) {
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
            if (!option.equals("default")) command.add("-Dawt.useSystemAAFontSettings=" + option);
            command.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName(), option));
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            for (String name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(name);
            Process process = builder.start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly().waitFor();
            String output = new String(process.getInputStream().readAllBytes());
            assertTrue(finished, output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Verified font hint: " + option), output);
        }
    }

    public static final class Probe {
        public static void main(String[] args) {
            CompanionApp.configureLookAndFeel();
            String expected = args[0].equals("default") ? "on" : args[0];
            if (!expected.equals(System.getProperty("awt.useSystemAAFontSettings")))
                throw new AssertionError("Unexpected font property: " + System.getProperty("awt.useSystemAAFontSettings"));
            var hints = (Map<?, ?>) Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            Object value = switch (expected) {
                case "on" -> RenderingHints.VALUE_TEXT_ANTIALIAS_ON;
                    case "off" -> null; // The JDK disables desktop font hints for this setting.
                case "lcd" -> RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB;
                default -> throw new AssertionError(expected);
            };
            Object actual = hints == null ? null : hints.get(RenderingHints.KEY_TEXT_ANTIALIASING);
            if (actual != value) throw new AssertionError("Unexpected desktop font hint for " + expected + ": " + hints);
            System.out.println("Verified font hint: " + args[0]);
            System.exit(0);
        }
    }
}
