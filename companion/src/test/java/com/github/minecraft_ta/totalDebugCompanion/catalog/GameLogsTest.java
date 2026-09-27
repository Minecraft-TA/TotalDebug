package com.github.minecraft_ta.totalDebugCompanion.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameLogsTest {
    @TempDir Path directory;

    @Test
    void aLogKeepsItsWarningsAndErrorsWithTheLinesThatFollow() throws Exception {
        String log = """
                [26Sept2026 15:58:19.545] [main/INFO] [cpw.mods.modlauncher.Launcher/MODLAUNCHER]: ModLauncher running
                [26Sept2026 15:58:20.583] [main/WARN] [CrashAssistantJarInJarHelper/]: Stream injection failed
                [26Sept2026 15:58:21.100] [Render thread/ERROR] [net.minecraft.client.Minecraft/]: Failed to load model
                java.io.FileNotFoundException: testmod:models/block/gear.json
                \tat net.minecraft.client.resources.model.ModelBakery.load(ModelBakery.java:42)
                [26Sept2026 15:58:22.000] [main/INFO] [net.neoforged.fml.loading/]: Done
                """;
        Path file = Files.createDirectories(this.directory.resolve("logs")).resolve("latest.log");
        Files.writeString(file, log);

        GameLogs.Log read = GameLogs.readLog(file);
        assertEquals(1, read.warnings());
        assertEquals(1, read.errors());
        GameLogs.LogEntry error = read.entries().get(1);
        assertEquals("net.minecraft.client.Minecraft", error.logger(), "the marker after the slash is dropped");
        assertEquals("Render thread", error.thread());
        assertEquals("Failed to load model", error.message());
        assertEquals(3, error.text().lines().count(), "the stack trace belongs to the error, the next header does not");
        assertEquals(log.indexOf("[26Sept2026 15:58:21.100]"), error.offset(), "the editor opens the log at the error");
    }

    @Test
    void offsetsCountLineBreaksAsTheFileHoldsThem() throws Exception {
        String log = "[t 1] [main/INFO] [a/]: first\r\n[t 2] [main/WARN] [b/]: second\r\n";
        Path file = Files.writeString(this.directory.resolve("latest.log"), log);

        assertEquals(log.indexOf("[t 2]"), GameLogs.readLog(file).entries().getFirst().offset(),
                "a log the game writes on Windows ends its lines with \\r\\n, which the editor keeps");
    }

    @Test
    void theCountsCoverEveryEntryEvenPastTheOnesKept() throws Exception {
        StringBuilder log = new StringBuilder();
        for (int line = 0; line < GameLogs.MAX_ENTRIES + 5; line++) log.append("[t] [main/WARN] [a/]: warning ").append(line).append('\n');
        Path file = Files.writeString(this.directory.resolve("debug.log"), log);

        GameLogs.Log read = GameLogs.readLog(file);
        assertEquals(GameLogs.MAX_ENTRIES, read.entries().size());
        assertEquals(GameLogs.MAX_ENTRIES + 5, read.warnings());
        assertEquals(true, read.truncated());
    }

    @Test
    void aMessageOverSeveralLinesIsOneExceptionAndAMixinHandlerBelongsToItsMod() throws Exception {
        String report = """
                Time: 2026-09-27 10:00:00
                Description: Ticking entity

                java.lang.IllegalStateException: Missing registry entries:
                  testmod:gear
                  testmod:cog
                \tat TRANSFORMER/minecraft@1.21.1/net.minecraft.server.level.ServerLevel.handler$zfe000$sodium$onTick(ServerLevel.java:9999) ~[?:?] {}
                """;
        Path file = Files.writeString(this.directory.resolve("crash.txt"), report);

        GameLogs.CrashReport read = GameLogs.readCrashReport(file);
        assertEquals(1, read.failures().size(), "the entries continue the message");
        assertEquals("java.lang.IllegalStateException: Missing registry entries:", read.failures().getFirst().message());
        assertEquals(3, read.failures().getFirst().text().lines().count());
        assertEquals("sodium", read.failures().getFirst().frames().getFirst().modId(),
                "a handler merged into Minecraft's class belongs to the mod whose mixin added it");
    }

    @Test
    void aCrashReportNamesItsCausesFramesAndTheModsTheyBelongTo() throws Exception {
        String report = """
                ---- Minecraft Crash Report ----
                // Daisy, daisy...

                Time: 2026-08-22 21:11:53
                Description: Unexpected error

                java.lang.IllegalStateException: TotalDebug client has not been initialized yet
                \tat TRANSFORMER/total_debug@2.0.0-SNAPSHOT/com.github.minecraft_ta.totaldebug.client.TotalDebugClient.get(TotalDebugClient.java:64) ~[total_debug.jar%23809!/:2.0.0] {re:classloading}
                \tat MC-BOOTSTRAP/org.spongepowered.mixin/org.spongepowered.asm.mixin.transformer.MixinProcessor.applyMixins(MixinProcessor.java:392) ~[?:?] {}
                \tat java.base/java.lang.reflect.Method.invoke(Method.java:580) ~[?:?] {}
                Caused by: java.lang.NullPointerException: client
                \tat TRANSFORMER/minecraft@1.21.1/net.minecraft.client.Minecraft.tick(Minecraft.java:1915) ~[client.jar%23509!/:?] {}
                \t... 26 more


                A detailed walkthrough of the error, its code path and all known details is as follows:
                ---------------------------------------------------------------------------------------
                -- Mod loading issue for: total_debug --
                Details:
                \tMod file: /C:/mods/total_debug.jar
                \tFailure message: Mod total_debug requires neoforge 21.1.248 or above
                """;
        Path file = Files.createDirectories(this.directory.resolve("crash-reports")).resolve("crash-2026-08-22_21.11.53-client.txt");
        Files.writeString(file, report);

        GameLogs.CrashReport read = GameLogs.readCrashReport(file);
        assertEquals("2026-08-22 21:11:53", read.time());
        assertEquals("Unexpected error", read.description());
        assertEquals(List.of("java.lang.IllegalStateException: TotalDebug client has not been initialized yet",
                "Caused by: java.lang.NullPointerException: client"), read.failures().stream().map(GameLogs.Failure::message).toList());
        List<GameLogs.Frame> frames = read.failures().getFirst().frames();
        assertEquals(List.of("TotalDebugClient.get", "MixinProcessor.applyMixins", "Method.invoke"),
                frames.stream().map(GameLogs.Frame::shortName).toList(), "a module without a version is still a frame");
        assertEquals(List.of("total_debug", "", ""), frames.stream().map(GameLogs.Frame::modId).toList(),
                "only transformed modules are mods");
        assertEquals("TotalDebugClient.java:64", frames.getFirst().source());
        assertEquals("minecraft", read.failures().getLast().frames().getFirst().modId());
        assertEquals(List.of(new GameLogs.ModIssue("total_debug", "Mod total_debug requires neoforge 21.1.248 or above",
                report.indexOf("-- Mod loading issue"))), read.modIssues());
    }

    @Test
    void emptyFoldersHoldNoLogs() throws Exception {
        Files.createDirectories(this.directory.resolve("logs"));
        Files.writeString(Files.createDirectories(this.directory.resolve("crash-reports")).resolve("notes.md"), "");
        assertEquals(false, GameLogs.any(this.directory), "the Logs row would open an empty page");

        Files.writeString(this.directory.resolve("crash-reports/crash-2026-09-27_10.00.00-client.txt"), "");
        assertEquals(true, GameLogs.any(this.directory));
    }

    @Test
    void theCurrentLogsComeFirstThenTheNewestCrashReports() throws Exception {
        Path logs = Files.createDirectories(this.directory.resolve("logs"));
        Files.writeString(logs.resolve("latest.log"), "");
        Files.writeString(logs.resolve("2026-08-20-1.log.gz"), "");
        Path crashes = Files.createDirectories(this.directory.resolve("crash-reports"));
        Path older = Files.writeString(crashes.resolve("crash-older.txt"), "");
        Path newer = Files.writeString(crashes.resolve("crash-newer.txt"), "");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000));

        assertEquals(List.of("latest.log", "crash-newer.txt", "crash-older.txt"),
                GameLogs.list(this.directory).stream().map(GameLogs.LogFile::name).toList());
    }
}
