package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.testui.UiTest;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@UiTest
class PrismInstancePickerTest {
    @TempDir Path root;

    @Test void filtersByNameAndVersionAndOpensOnlyTheSelectedInstance() throws Exception {
        var current = instance("Z Current", "1.21.1");
        var other = instance("A Other", "1.20.1");
        var opened = new AtomicReference<CompanionProfile>();
        var owner = new AtomicReference<JFrame>();
        var dialog = new AtomicReference<PrismInstancePicker>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                owner.set(new JFrame());
                dialog.set(new PrismInstancePicker(owner.get(), root, current, opened::set));
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (dialog.get().isLoading() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(dialog.get().isLoading(), "Instance discovery did not complete");
            SwingUtilities.invokeAndWait(() -> {
                var picker = dialog.get();
                var search = find(picker, JTextField.class);
                var list = find(picker, JList.class);
                var open = picker.getRootPane().getDefaultButton();
                assertNotNull(search);
                assertNotNull(list);
                assertEquals(2, list.getModel().getSize());
                Object currentEntry = list.getSelectedValue();
                search.setText("z current");
                assertEquals(1, list.getModel().getSize());
                assertSame(currentEntry, list.getSelectedValue(), "Current project should be selected first");
                search.setText("missing");
                assertEquals(0, list.getModel().getSize());
                assertFalse(open.isEnabled());
                open.doClick(0);
                assertNull(opened.get());
                search.setText("1.20.1");
                assertEquals(1, list.getModel().getSize());
                assertTrue(open.isEnabled());
                open.doClick(0);
                assertEquals(other, opened.get());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialog.get() != null) dialog.get().dispose();
                if (owner.get() != null) owner.get().dispose();
            });
        }
    }

    private CompanionProfile instance(String name, String version) throws Exception {
        Path game = Files.createDirectories(root.resolve("instances").resolve(name).resolve("minecraft"));
        Files.writeString(game.getParent().resolve("instance.cfg"), "[General]\n");
        Files.writeString(game.getParent().resolve("mmc-pack.json"),
                "{\"components\":[{\"uid\":\"net.minecraft\",\"version\":\"" + version + "\"}]}");
        return CompanionProfile.forGame(game);
    }

    private static <T> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T result = find(nested, type);
                if (result != null) return result;
            }
        }
        return null;
    }
}
