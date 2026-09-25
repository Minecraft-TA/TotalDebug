package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigChanges;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigSources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigPanelTest {
    @TempDir Path directory;

    private static final PackCatalog.ConfigFile FILE = new PackCatalog.ConfigFile("testmod-server.toml",
            PackCatalog.ConfigType.SERVER, null,
            List.of(new PackCatalog.ConfigSection("widgets", "Widget behavior")),
            List.of(new PackCatalog.ConfigSetting("widgets.speed", "How fast widgets spin", "4", "1 ~ 16", List.of(),
                            PackCatalog.Restart.NONE),
                    new PackCatalog.ConfigSetting("widgets.mode", "", "FAST", "", List.of("FAST", "SLOW"),
                            PackCatalog.Restart.WORLD),
                    new PackCatalog.ConfigSetting("enabled", "", "true", "", List.of(), PackCatalog.Restart.GAME)));

    @Test
    void rowsGroupSettingsUnderSectionsAndMarkChangedValues() throws Exception {
        ConfigValues values = ConfigValues.parse("""
                enabled = true
                [widgets]
                speed = 9
                mode = "FAST"
                """, FILE.fileName());

        List<ConfigSettingsTable.Row> rows = ConfigSettingsTable.rows(FILE, values);

        assertEquals(List.of("widgets", "widgets.speed", "widgets.mode", "enabled"),
                rows.stream().map(ConfigSettingsTable.Row::path).toList());
        assertEquals("Widget behavior", rows.getFirst().comment());
        assertTrue(rows.get(1).modified());
        assertFalse(rows.get(2).modified());
        assertEquals("FAST, SLOW", rows.get(2).accepts());
        assertEquals("1 to 16", rows.get(1).accepts());
        assertEquals(ConfigSettingsTable.ValueKind.NUMBER, rows.get(1).kind());
        assertEquals(ConfigSettingsTable.ValueKind.CHOICE, rows.get(2).kind());
        assertEquals(ConfigSettingsTable.ValueKind.BOOLEAN, rows.get(3).kind());
        assertEquals("\"FAST\"", rows.get(2).literal());
        String speed = ConfigSettingsTable.tooltip(rows.get(1), null, null);
        assertTrue(speed.contains("widgets.speed") && speed.contains("How fast widgets spin")
                && speed.contains("Accepts 1 to 16") && speed.contains(">4</font>"), speed);
        assertTrue(ConfigSettingsTable.tooltip(rows.get(2), null, null).contains("Takes effect after rejoining the world"));
        String edited = ConfigSettingsTable.tooltip(rows.get(3), ConfigChanges.Effect.RESTART, "false");
        assertTrue(edited.contains("takes effect after restarting the game") && edited.contains("Before your edit"), edited);
    }

    @Test
    void anEditedValueIsWrittenRefusedOrUndone() throws Exception {
        Path file = Files.createDirectories(this.directory.resolve("config")).resolve("testmod-common.toml");
        Files.writeString(file, """
                #Widget behavior
                [widgets]
                \t#How fast widgets spin
                \tspeed = 9
                \tmode = "SLOW"
                """);
        PackCatalog.ConfigFile common = new PackCatalog.ConfigFile("testmod-common.toml", PackCatalog.ConfigType.COMMON,
                file, FILE.sections(), FILE.settings().subList(0, 2));
        ConfigPanel[] panel = new ConfigPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ConfigPanel(this.directory, new ConfigChanges(this.directory), target -> { });
            panel[0].setFiles(List.of(common));
        });
        ConfigSettingsTable table = component(panel[0], ConfigSettingsTable.class);
        awaitOnSwing(() -> table.getRowCount() == 3 && table.row(1).literal() != null);

        SwingUtilities.invokeAndWait(() -> {
            assertTrue(table.edit(1));
            ((JTextField) table.getEditorComponent()).setText("20");
            assertFalse(table.getCellEditor().stopCellEditing());
            assertEquals("speed: Accepts 1 to 16", component(panel[0], JLabel.class, "speed: Accepts 1 to 16").getText());
            ((JTextField) table.getEditorComponent()).setText("12");
            assertTrue(table.getCellEditor().stopCellEditing());
        });
        awaitOnSwing(() -> table.getRowCount() == 3 && "12".equals(table.row(1).value()));
        assertTrue(Files.readString(file).contains("\tspeed = 12\n"));
        assertTrue(Files.readString(file).contains("\t#How fast widgets spin\n"));
        component(panel[0], JLabel.class, "speed saved, applies when the game starts");

        SwingUtilities.invokeAndWait(() -> table.getActionMap().get("undoConfigEdit").actionPerformed(null));
        awaitOnSwing(() -> "9".equals(table.row(1).value()));
        SwingUtilities.invokeAndWait(() -> table.getActionMap().get("redoConfigEdit").actionPerformed(null));
        awaitOnSwing(() -> "12".equals(table.row(1).value()));

        SwingUtilities.invokeAndWait(() -> table.reset(table.row(1)));
        awaitOnSwing(() -> "4".equals(table.row(1).value()));
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(table.edit(2));
            JComboBox<?> choices = (JComboBox<?>) table.getEditorComponent();
            choices.setSelectedItem("FAST");
        });
        awaitOnSwing(() -> "FAST".equals(table.row(2).value()));
        assertTrue(Files.readString(file).contains("\tmode = \"FAST\"\n"));
    }

    /** Waits until {@code condition}, checked on the Swing thread, holds. */
    private static void awaitOnSwing(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean[] met = new boolean[1];
        while (true) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting on the Swing thread");
            Thread.sleep(20);
        }
    }

    private static <T extends Component> T component(Container root, Class<T> type) {
        return component(root, type, null);
    }

    /** The first component of {@code type} below {@code root}, a label with {@code text} when given. */
    private static <T extends Component> T component(Container root, Class<T> type, String text) {
        T found = find(root, type, text);
        if (found == null) throw new AssertionError("No " + type.getSimpleName() + (text == null ? "" : " showing " + text));
        return found;
    }

    private static <T extends Component> T find(Container root, Class<T> type, String text) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && (text == null || child instanceof JLabel label && text.equals(label.getText()))) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                T found = find(container, type, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void aMissingFileShowsTheDefaults() {
        List<ConfigSettingsTable.Row> rows = ConfigSettingsTable.rows(FILE, null);

        assertEquals("4", rows.get(1).value());
        assertFalse(rows.get(1).modified());
    }

    @Test
    void serverConfigurationsComeFromEachWorldNewestFirstThenTheDefaults() throws Exception {
        Path older = world("Old World", 1_000);
        Path newer = world("New World", 2_000);
        Path defaults = Files.createDirectories(this.directory.resolve("defaultconfigs")).resolve(FILE.fileName());
        Files.writeString(defaults, "");

        assertEquals(List.of(new ConfigSources.Source("New World", newer), new ConfigSources.Source("Old World", older),
                new ConfigSources.Source("New worlds", defaults)), ConfigSources.of(this.directory, FILE));
    }

    private Path world(String name, long modified) throws Exception {
        Path file = Files.createDirectories(this.directory.resolve("saves").resolve(name).resolve("serverconfig"))
                .resolve(FILE.fileName());
        Files.writeString(file, "");
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return file;
    }
}
