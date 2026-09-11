package com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch;

import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.tree.TreePath;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * IntelliJ-style speed search for navigation components. Typing keeps focus in the target and moves
 * its selection; it never filters or mutates the target model.
 */
public final class SpeedSearch implements AutoCloseable {
    private static final String TARGET_PROPERTY = SpeedSearch.class.getName() + ".target";
    private static final String INPUT_PROPERTY = SpeedSearch.class.getName() + ".input";

    private final SpeedSearchTarget target;
    private final JComponent inputSource;
    private final SpeedSearchPopup popup = new SpeedSearchPopup();
    private final StringBuilder query = new StringBuilder();
    private final KeyAdapter keys = new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent event) {
            handlePressed(event);
        }

        @Override
        public void keyTyped(KeyEvent event) {
            handleTyped(event);
        }
    };
    private final FocusAdapter focus = new FocusAdapter() {
        @Override
        public void focusLost(FocusEvent event) {
            deactivate();
        }
    };
    private final MouseAdapter mouse = new MouseAdapter() {
        @Override
        public void mousePressed(MouseEvent event) {
            deactivate();
        }
    };
    private final ComponentAdapter visibility = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent event) {
            updatePopup();
        }

        @Override
        public void componentMoved(ComponentEvent event) {
            updatePopup();
        }

        @Override
        public void componentShown(ComponentEvent event) {
            updatePopup();
        }

        @Override
        public void componentHidden(ComponentEvent event) {
            deactivate();
        }
    };

    private boolean active;
    private boolean closed;

    private SpeedSearch(SpeedSearchTarget target, JComponent inputSource) {
        this.target = Objects.requireNonNull(target, "target");
        this.inputSource = Objects.requireNonNull(inputSource, "inputSource");

        SpeedSearch previousTarget = existing(target.component(), TARGET_PROPERTY);
        if (previousTarget != null) {
            previousTarget.close();
        }
        SpeedSearch previousInput = existing(inputSource, INPUT_PROPERTY);
        if (previousInput != null && previousInput != previousTarget) {
            previousInput.close();
        }

        target.component().putClientProperty(TARGET_PROPERTY, this);
        inputSource.putClientProperty(INPUT_PROPERTY, this);
        inputSource.addKeyListener(this.keys);
        inputSource.addFocusListener(this.focus);
        target.component().addMouseListener(this.mouse);
        target.component().addComponentListener(this.visibility);
        target.installContentListener(this::contentChanged);
    }

    public static <T> SpeedSearch install(JList<T> list, Function<? super T, String> text) {
        return install(list, list, text);
    }

    /** Explicit input routing for non-focusable chooser lists whose editor invoker retains focus. */
    public static <T> SpeedSearch install(
            JList<T> list,
            JComponent inputSource,
            Function<? super T, String> text
    ) {
        return new SpeedSearch(SpeedSearchTargets.list(list, text), inputSource);
    }

    public static SpeedSearch install(JTree tree, Function<? super TreePath, String> text) {
        return new SpeedSearch(SpeedSearchTargets.tree(tree, text), tree);
    }

    public static SpeedSearch install(JTable table, IntFunction<String> text) {
        return new SpeedSearch(SpeedSearchTargets.table(table, text), table);
    }

    public static SpeedSearch install(JTabbedPane tabs, IntFunction<String> text) {
        return new SpeedSearch(SpeedSearchTargets.tabs(tabs, text), tabs);
    }

    public boolean isActive() {
        return this.active;
    }

    public String query() {
        return this.query.toString();
    }

    /** Match fragments for renderers owned by an installed speed-search target. */
    public static List<MatchRange> matchingRanges(JComponent owner, String text) {
        SpeedSearch search = existing(owner, TARGET_PROPERTY);
        return search == null || !search.active
                ? List.of()
                : SpeedSearchMatcher.match(text, search.query());
    }

    private static SpeedSearch existing(JComponent component, String property) {
        return component.getClientProperty(property) instanceof SpeedSearch search ? search : null;
    }

    private void handlePressed(KeyEvent event) {
        if (isFindShortcut(event)) {
            activate();
            event.consume();
            return;
        }
        if (!this.active) {
            return;
        }

        switch (event.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> {
                deactivate();
                event.consume();
            }
            case KeyEvent.VK_BACK_SPACE -> {
                removeLastCharacter();
                event.consume();
            }
            case KeyEvent.VK_UP -> {
                move(-1);
                event.consume();
            }
            case KeyEvent.VK_DOWN -> {
                move(1);
                event.consume();
            }
            case KeyEvent.VK_HOME -> {
                selectBoundary(false);
                event.consume();
            }
            case KeyEvent.VK_END -> {
                selectBoundary(true);
                event.consume();
            }
            case KeyEvent.VK_ENTER -> deactivate();
            default -> {
            }
        }
    }

    private void handleTyped(KeyEvent event) {
        char character = event.getKeyChar();
        if (event.isAltDown() || event.isControlDown() || event.isMetaDown()
                || Character.isISOControl(character)
                || !this.active && Character.isWhitespace(character)) {
            return;
        }
        activate();
        this.query.append(character);
        updateSelection();
        event.consume();
    }

    private void activate() {
        if (this.closed) {
            return;
        }
        this.active = true;
        updatePopup();
        this.target.component().repaint();
    }

    private void deactivate() {
        if (!this.active) {
            return;
        }
        this.active = false;
        this.query.setLength(0);
        this.popup.hidePopup();
        this.target.component().repaint();
    }

    private void removeLastCharacter() {
        if (this.query.isEmpty()) {
            deactivate();
            return;
        }
        int lastCodePoint = this.query.offsetByCodePoints(this.query.length(), -1);
        this.query.delete(lastCodePoint, this.query.length());
        if (this.query.isEmpty()) {
            deactivate();
        } else {
            updateSelection();
        }
    }

    private void updateSelection() {
        List<Integer> matches = matches();
        if (!matches.isEmpty() && !matches.contains(this.target.selectedIndex())) {
            int selected = this.target.selectedIndex();
            int next = matches.stream().filter(index -> index >= selected).findFirst().orElse(matches.getFirst());
            this.target.select(next);
        }
        updatePopup(matches);
        this.target.component().repaint();
    }

    private void move(int direction) {
        List<Integer> matches = matches();
        if (matches.isEmpty()) {
            updatePopup(matches);
            return;
        }
        int matchIndex = matches.indexOf(this.target.selectedIndex());
        int next = matchIndex < 0
                ? direction > 0 ? 0 : matches.size() - 1
                : Math.floorMod(matchIndex + direction, matches.size());
        this.target.select(matches.get(next));
        updatePopup(matches);
    }

    private void selectBoundary(boolean end) {
        List<Integer> matches = matches();
        if (!matches.isEmpty()) {
            this.target.select(end ? matches.getLast() : matches.getFirst());
        }
        updatePopup(matches);
    }

    private List<Integer> matches() {
        if (this.query.isEmpty()) {
            return List.of();
        }
        String currentQuery = this.query();
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < this.target.size(); index++) {
            if (!SpeedSearchMatcher.match(safeText(index), currentQuery).isEmpty()) {
                matches.add(index);
            }
        }
        return matches;
    }

    private String safeText(int index) {
        String text = this.target.textAt(index);
        return text == null ? "" : text;
    }

    private void updatePopup() {
        updatePopup(matches());
    }

    private void updatePopup(List<Integer> matches) {
        if (!this.active) {
            return;
        }
        this.popup.showFor(this.target.component(), this.query(), this.query.isEmpty() || !matches.isEmpty());
    }

    private void contentChanged() {
        if (!this.active) {
            return;
        }
        UIUtils.onEdt(this::updateSelection);
    }

    private static boolean isFindShortcut(KeyEvent event) {
        return event.getKeyCode() == KeyEvent.VK_F
                && (event.isControlDown() || event.isMetaDown())
                && !event.isAltDown();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        deactivate();
        this.closed = true;
        this.inputSource.removeKeyListener(this.keys);
        this.inputSource.removeFocusListener(this.focus);
        this.target.component().removeMouseListener(this.mouse);
        this.target.component().removeComponentListener(this.visibility);
        this.target.dispose();
        if (existing(this.target.component(), TARGET_PROPERTY) == this) {
            this.target.component().putClientProperty(TARGET_PROPERTY, null);
        }
        if (existing(this.inputSource, INPUT_PROPERTY) == this) {
            this.inputSource.putClientProperty(INPUT_PROPERTY, null);
        }
    }

    public record MatchRange(int start, int end) {
        public MatchRange {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("A match range must be non-empty and non-negative");
            }
        }
    }
}
