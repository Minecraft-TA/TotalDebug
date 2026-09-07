package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Renders the complete dark/light scenario matrix in isolated Companion processes. */
public final class UiCaptureSuite {
    private static final int CAPTURE_TIMEOUT_SECONDS = 30;
    private static final int SHEET_COLUMNS = 2;
    private static final int TILE_LABEL_HEIGHT = 36;

    private UiCaptureSuite() {
    }

    public static void main(String[] args) throws Exception {
        Path output = argument(args, "--output=")
                .map(Path::of)
                .orElse(Path.of("build", "ui-screenshots"))
                .toAbsolutePath()
                .normalize();
        boolean assembleOnly = java.util.Arrays.asList(args).contains("--assemble-only");
        Files.createDirectories(output);

        List<Capture> captures = new ArrayList<>();
        for (CompanionTheme theme : CompanionTheme.available()) {
            for (UiRenderScenario scenario : UiRenderScenario.values()) {
                Path image = output.resolve(theme.id()).resolve(scenario.id() + ".png");
                if (assembleOnly) {
                    if (!Files.isRegularFile(image)) {
                        throw new IllegalStateException("Missing UI capture: " + image);
                    }
                } else {
                    renderInFreshProcess(theme.id(), scenario, image);
                }
                captures.add(new Capture(theme.id(), scenario, image));
            }
        }

        writeContactSheet(output.resolve("contact-sheet.png"), captures);
        for (CompanionTheme theme : CompanionTheme.available()) {
            writeContactSheet(
                    output.resolve("contact-sheet-" + theme.id() + ".png"),
                    captures.stream().filter(capture -> capture.theme().equals(theme.id())).toList()
            );
        }
        writeManifest(output.resolve("manifest.json"), captures);
        System.out.println("UI capture suite: " + output);
    }

    private static void renderInFreshProcess(String theme, UiRenderScenario scenario, Path image)
            throws IOException, InterruptedException {
        Files.createDirectories(image.getParent());
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"
        );
        Process process = new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                UiDevHarness.class.getName(),
                "--theme=" + theme,
                "--scenario=" + scenario.id(),
                "--screenshot=" + image
        ).inheritIO().start();
        if (!process.waitFor(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("UI scenario timed out: " + theme + '/' + scenario.id());
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "UI scenario failed with exit code " + process.exitValue() + ": "
                            + theme + '/' + scenario.id()
            );
        }
    }

    private static void writeContactSheet(Path target, List<Capture> captures) throws IOException {
        if (captures.isEmpty()) {
            throw new IllegalArgumentException("A contact sheet requires at least one capture");
        }
        BufferedImage first = ImageIO.read(captures.getFirst().image().toFile());
        int tileWidth = first.getWidth();
        int tileImageHeight = first.getHeight();
        int rows = Math.ceilDiv(captures.size(), SHEET_COLUMNS);
        int tileHeight = TILE_LABEL_HEIGHT + tileImageHeight;
        BufferedImage sheet = new BufferedImage(
                SHEET_COLUMNS * tileWidth,
                rows * tileHeight,
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = sheet.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(0x202124));
        graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));

        for (int index = 0; index < captures.size(); index++) {
            Capture capture = captures.get(index);
            int x = index % SHEET_COLUMNS * tileWidth;
            int y = index / SHEET_COLUMNS * tileHeight;
            graphics.setColor(new Color(0xD7DAE0));
            graphics.drawString(capture.theme() + "  /  " + capture.scenario().id(), x + 10, y + 24);
            BufferedImage image = ImageIO.read(capture.image().toFile());
            graphics.drawImage(image, x, y + TILE_LABEL_HEIGHT, null);
        }
        graphics.dispose();
        ImageIO.write(sheet, "png", target.toFile());
    }

    private static void writeManifest(Path target, List<Capture> captures) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"generatedAt\": \"").append(Instant.now()).append("\",\n");
        json.append("  \"captures\": [\n");
        for (int index = 0; index < captures.size(); index++) {
            Capture capture = captures.get(index);
            Path relative = target.getParent().relativize(capture.image());
            json.append("    {\"theme\": \"").append(capture.theme())
                    .append("\", \"scenario\": \"").append(capture.scenario().id())
                    .append("\", \"description\": \"").append(capture.scenario().description())
                    .append("\", \"file\": \"").append(relative.toString().replace('\\', '/'))
                    .append("\"}");
            json.append(index + 1 == captures.size() ? '\n' : ",\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(target, json, StandardCharsets.UTF_8);
    }

    private static java.util.Optional<String> argument(String[] args, String prefix) {
        return java.util.Arrays.stream(args)
                .filter(argument -> argument.startsWith(prefix))
                .map(argument -> argument.substring(prefix.length()))
                .findFirst();
    }

    private record Capture(String theme, UiRenderScenario scenario, Path image) {
    }
}
