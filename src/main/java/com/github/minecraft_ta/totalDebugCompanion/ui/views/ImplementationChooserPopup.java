package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyDirection;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyPage;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyQuery;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyResult;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.RuntimeMember;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.ui.HierarchyPresentation;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.RuntimeModulePresentation;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.Objects;

/** Async implementation and base-declaration chooser shared by code vision, gutter markers and Ctrl+T/U. */
public final class ImplementationChooserPopup extends BasePopup {
    private static final int RESULT_LIMIT = 1_000;
    private static final String RESULTS_CARD = "results";
    private static final String MESSAGE_CARD = "message";

    private final CodeInsightService service;
    private final JLabel title = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel message = new JLabel("Looking up the hierarchy...", SwingConstants.CENTER);
    private final JCheckBox directOnly = new JCheckBox("Direct subtypes only");
    private final DefaultListModel<HierarchyResult> listModel = new DefaultListModel<>();
    private final JList<HierarchyResult> list = new JList<>(this.listModel);
    private final JPanel cards = new JPanel(new CardLayout());

    private CodeInsightService.SearchHandle activeSearch;
    private CodeInsightService.SearchHandle directNavigationSearch;
    private HierarchyQuery query;
    private HierarchyRelation relation;
    private JTextComponent invoker;
    private int anchorOffset;
    private long generation;
    private boolean updatingOptions;
    private final KeyAdapter invokerKeys = new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent event) {
            if (!isVisible()) {
                return;
            }
            switch (event.getKeyCode()) {
                case KeyEvent.VK_ENTER -> openSelected();
                case KeyEvent.VK_UP -> moveSelection(-1);
                case KeyEvent.VK_DOWN -> moveSelection(1);
                case KeyEvent.VK_ESCAPE -> setVisible(false);
                default -> {
                    return;
                }
            }
            event.consume();
        }
    };

    public ImplementationChooserPopup(Window owner, CodeInsightService service) {
        super(owner);
        this.service = Objects.requireNonNull(service, "service");
        configureUi();
    }

    public void navigate(
            JTextComponent editor,
            CodeSymbol symbol,
            HierarchyRelation relation,
            int count,
            int offset
    ) {
        Objects.requireNonNull(editor, "editor");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(relation, "relation");
        if (count < 1) {
            throw new IllegalArgumentException("Hierarchy target count must be positive");
        }
        HierarchyQuery query = queryFor(symbol, relation);
        if (count != 1) {
            show(editor, offset, query, relation);
            return;
        }

        if (this.directNavigationSearch != null) {
            this.directNavigationSearch.cancel();
        }
        this.directNavigationSearch = this.service.search(query, 2, new CodeInsightService.Listener<>() {
            @Override
            public void onCompleted(HierarchyPage page) {
                directNavigationSearch = null;
                if (page.results().size() == 1 && !page.truncated()) {
                    openResult(page.results().getFirst());
                } else {
                    show(editor, offset, query, relation);
                }
            }

            @Override
            public void onFailed(Throwable failure) {
                directNavigationSearch = null;
                showDirectFailure(editor, offset, query, relation, failure);
            }
        });
    }

    public void setListFont(Font font) {
        this.list.setFont(Objects.requireNonNull(font, "font"));
    }

    private void show(
            JTextComponent editor,
            int offset,
            HierarchyQuery nextQuery,
            HierarchyRelation nextRelation
    ) {
        prepare(editor, offset, nextQuery, nextRelation);
        startSearch();
        showAtAnchor(editor, offset);
    }

    private void showDirectFailure(
            JTextComponent editor,
            int offset,
            HierarchyQuery query,
            HierarchyRelation relation,
            Throwable failure
    ) {
        prepare(editor, offset, query, relation);
        this.listModel.clear();
        this.message.setText("Hierarchy lookup failed: " + failure.getClass().getSimpleName());
        showCard(MESSAGE_CARD);
        updateTitle(null);
        packForLoading();
        failure.printStackTrace(System.err);
        showAtAnchor(editor, offset);
    }

    private void prepare(
            JTextComponent editor,
            int offset,
            HierarchyQuery nextQuery,
            HierarchyRelation nextRelation
    ) {
        Objects.requireNonNull(editor, "editor");
        this.invoker = editor;
        this.anchorOffset = offset;
        this.query = nextQuery;
        this.relation = nextRelation;
        this.updatingOptions = true;
        this.directOnly.setSelected(nextQuery.directSubtypesOnly());
        this.directOnly.setVisible(nextQuery.symbol() instanceof CodeSymbol.ClassSymbol
                && nextQuery.direction() == HierarchyDirection.IMPLEMENTATIONS);
        this.updatingOptions = false;
    }

    private void showAtAnchor(JTextComponent editor, int offset) {
        try {
            Rectangle2D anchor = editor.modelToView2D(offset);
            packForLoading();
            super.show(
                    editor,
                    (int) anchor.getX(),
                    (int) (anchor.getY() + anchor.getHeight()),
                    Alignment.BOTTOM_CENTER
            );
            fitToEditor(editor, anchor);
            installInvokerKeys();
        } catch (BadLocationException exception) {
            throw new IllegalArgumentException("Invalid hierarchy popup offset " + offset, exception);
        }
    }

    private void configureUi() {
        JPanel content = (JPanel) getContentPane();
        content.setBorder(DynamicMatteBorder.rule(1, 1, 1, 1));

        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(DynamicMatteBorder.separatorRule(0, 0, 1, 0));
        header.add(this.title, BorderLayout.CENTER);
        header.add(this.directOnly, BorderLayout.EAST);
        this.title.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        this.directOnly.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 10));
        add(header, BorderLayout.NORTH);

        this.list.setCellRenderer(new ResultRenderer());
        this.list.setFixedCellHeight(28);
        this.list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        this.list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2) {
                    openSelected();
                }
            }
        });
        JScrollPane scrollPane = new JScrollPane(this.list);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        this.cards.add(scrollPane, RESULTS_CARD);
        this.cards.add(this.message, MESSAGE_CARD);
        add(this.cards, BorderLayout.CENTER);

        this.directOnly.addActionListener(event -> {
            if (this.updatingOptions || !(this.query.symbol() instanceof CodeSymbol.ClassSymbol type)) {
                return;
            }
            this.query = HierarchyQuery.implementations(type, this.directOnly.isSelected());
            startSearch();
        });
    }

    private void startSearch() {
        if (this.activeSearch != null) {
            this.activeSearch.cancel();
        }
        long searchGeneration = ++this.generation;
        this.listModel.clear();
        this.message.setText("Looking up the hierarchy...");
        showCard(MESSAGE_CARD);
        updateTitle(null);
        packForLoading();

        this.activeSearch = this.service.search(this.query, RESULT_LIMIT, new CodeInsightService.Listener<>() {
            @Override
            public void onCompleted(HierarchyPage result) {
                if (searchGeneration == generation) {
                    showResult(result);
                }
            }

            @Override
            public void onFailed(Throwable failure) {
                if (searchGeneration == generation) {
                    showFailure(failure);
                }
            }
        });
    }

    private void showResult(HierarchyPage page) {
        this.activeSearch = null;
        this.listModel.addAll(page.results());
        updateTitle(page);
        if (page.results().isEmpty()) {
            this.message.setText(HierarchyPresentation.emptyResult(this.relation));
            showCard(MESSAGE_CARD);
        } else {
            this.list.setSelectedIndex(0);
            showCard(RESULTS_CARD);
        }
        resizeForResults(page.results().size());
    }

    private void showFailure(Throwable failure) {
        this.activeSearch = null;
        this.message.setText("Hierarchy lookup failed: " + failure.getClass().getSimpleName());
        showCard(MESSAGE_CARD);
        packForLoading();
        failure.printStackTrace(System.err);
    }

    private void updateTitle(HierarchyPage page) {
        String action = HierarchyPresentation.chooserAction(this.relation);
        String suffix = page == null ? "" : " (" + page.results().size()
                + (page.truncated() ? "+" : "") + " found)";
        this.title.setText(action + targetLabel(this.query.symbol()) + suffix);
    }

    private void installInvokerKeys() {
        this.invoker.removeKeyListener(this.invokerKeys);
        this.invoker.addKeyListener(this.invokerKeys);
    }

    private void openSelected() {
        HierarchyResult selected = this.list.getSelectedValue();
        if (selected == null) {
            return;
        }
        openResult(selected);
        setVisible(false);
    }

    private static void openResult(HierarchyResult result) {
        switch (result.symbol()) {
            case CodeSymbol.ClassSymbol type -> MainWindow.INSTANCE.navigation().navigate(
                    new NavigationTarget.RuntimeClass(type.className())
            );
            case CodeSymbol.MethodSymbol method -> MainWindow.INSTANCE.navigation().navigate(
                    new NavigationTarget.RuntimeDeclaration(RuntimeMember.from(method))
            );
            case CodeSymbol.FieldSymbol ignored -> throw new IllegalStateException(
                    "Hierarchy result unexpectedly contains a field"
            );
        }
    }

    private static HierarchyQuery queryFor(CodeSymbol symbol, HierarchyRelation relation) {
        if (relation.direction() == HierarchyDirection.IMPLEMENTATIONS) {
            if (symbol instanceof CodeSymbol.FieldSymbol) {
                throw new IllegalArgumentException("Fields do not have implementations");
            }
            return HierarchyQuery.implementations(symbol);
        }
        if (!(symbol instanceof CodeSymbol.MethodSymbol method)) {
            throw new IllegalArgumentException("Only methods have base declarations");
        }
        return HierarchyQuery.baseMethods(method);
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (!visible && this.directNavigationSearch != null) {
            this.directNavigationSearch.cancel();
            this.directNavigationSearch = null;
        }
        if (!visible && this.activeSearch != null) {
            this.activeSearch.cancel();
            this.activeSearch = null;
        }
        if (!visible && this.invoker != null) {
            this.invoker.removeKeyListener(this.invokerKeys);
        }
    }

    @Override
    public void dispose() {
        if (this.directNavigationSearch != null) {
            this.directNavigationSearch.cancel();
            this.directNavigationSearch = null;
        }
        super.dispose();
    }

    private void showCard(String card) {
        ((CardLayout) this.cards.getLayout()).show(this.cards, card);
    }

    private void packForLoading() {
        this.cards.setPreferredSize(new Dimension(680, 58));
        pack();
    }

    private void resizeForResults(int count) {
        int rows = Math.max(2, Math.min(10, count));
        this.cards.setPreferredSize(new Dimension(760, rows * this.list.getFixedCellHeight() + 4));
        pack();
        if (isVisible() && this.invoker != null) {
            try {
                fitToEditor(this.invoker, this.invoker.modelToView2D(this.anchorOffset));
            } catch (BadLocationException ignored) {
            }
        }
    }

    private void fitToEditor(JTextComponent editor, Rectangle2D anchor) {
        var editorLocation = editor.getLocationOnScreen();
        Rectangle visibleEditor = editor.getVisibleRect();
        int minimumX = editorLocation.x + visibleEditor.x;
        int maximumX = Math.max(minimumX, minimumX + visibleEditor.width - getWidth());
        int fittedX = Math.max(minimumX, Math.min(getX(), maximumX));

        Rectangle screen = editor.getGraphicsConfiguration().getBounds();
        int fittedY = getY();
        if (fittedY + getHeight() > screen.y + screen.height) {
            fittedY = editorLocation.y + (int) anchor.getY() - getHeight();
        }
        setLocation(fittedX, Math.max(screen.y, fittedY));
    }

    static String targetLabel(CodeSymbol symbol) {
        return switch (symbol) {
            case CodeSymbol.ClassSymbol type -> simpleClassName(type.className());
            case CodeSymbol.MethodSymbol method -> simpleClassName(method.ownerClassName()) + '.'
                    + displayMethod(method);
            case CodeSymbol.FieldSymbol field -> field.name();
        };
    }

    private static String displayMethod(CodeSymbol.MethodSymbol method) {
        String name = "<init>".equals(method.name())
                ? simpleClassName(method.ownerClassName())
                : method.name();
        return name + '(' + String.join(", ", java.util.Arrays.stream(
                        org.objectweb.asm.Type.getArgumentTypes(method.descriptor()))
                .map(ImplementationChooserPopup::simpleTypeName)
                .toList()) + ')';
    }

    private static String simpleTypeName(org.objectweb.asm.Type type) {
        if (type.getSort() == org.objectweb.asm.Type.ARRAY) {
            return simpleTypeName(type.getElementType()) + "[]".repeat(type.getDimensions());
        }
        String className = type.getClassName();
        return className.substring(className.lastIndexOf('.') + 1).replace('$', '.');
    }

    private static String simpleClassName(String binaryName) {
        String simple = binaryName.substring(binaryName.lastIndexOf('.') + 1);
        int anonymous = anonymousSeparator(simple);
        return anonymous >= 0 ? "Anonymous in " + simple.substring(0, anonymous) : simple.replace('$', '.');
    }

    private static int anonymousSeparator(String simpleName) {
        for (int index = 0; index < simpleName.length() - 1; index++) {
            if (simpleName.charAt(index) == '$' && Character.isDigit(simpleName.charAt(index + 1))) {
                return index;
            }
        }
        return -1;
    }

    private static String packageName(String binaryName) {
        int separator = binaryName.lastIndexOf('.');
        return separator < 0 ? "" : binaryName.substring(0, separator);
    }

    static PrimarySecondaryText resultPresentation(CodeSymbol symbol) {
        String owner = symbol.ownerClassName();
        String primary = switch (symbol) {
            case CodeSymbol.ClassSymbol ignored -> simpleClassName(owner);
            case CodeSymbol.MethodSymbol method -> simpleClassName(owner) + '.' + displayMethod(method);
            case CodeSymbol.FieldSymbol field -> simpleClassName(owner) + '.' + field.name();
        };
        return new PrimarySecondaryText(primary, packageName(owner));
    }

    static Icon symbolIcon(CodeSymbol symbol) {
        return symbol instanceof CodeSymbol.ClassSymbol ? Icons.JAVA_CLASS : Icons.JAVA_METHOD;
    }

    private void moveSelection(int direction) {
        int size = this.listModel.size();
        if (size == 0) {
            return;
        }
        int selected = this.list.getSelectedIndex();
        selected = selected < 0 ? 0 : Math.floorMod(selected + direction, size);
        this.list.setSelectedIndex(selected);
        this.list.ensureIndexIsVisible(selected);
    }

    private final class ResultRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> owner,
                Object value,
                int index,
                boolean selected,
                boolean focused
        ) {
            HierarchyResult result = (HierarchyResult) value;
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 8));
            Color background = selected ? owner.getSelectionBackground() : owner.getBackground();
            Color foreground = selected ? owner.getSelectionForeground() : ThemeColors.text();
            row.setBackground(background);

            PrimarySecondaryLabel declaration = new PrimarySecondaryLabel();
            declaration.configure(
                    resultPresentation(result.symbol()),
                    symbolIcon(result.symbol()),
                    owner.getFont(),
                    selected,
                    foreground
            );
            row.add(declaration, BorderLayout.CENTER);

            RuntimeModulePresentation module = RuntimeModulePresentation.of(
                    service.sourceCatalog().sourceFor(result.sourceId())
            );
            PrimarySecondaryLabel moduleLabel = new PrimarySecondaryLabel();
            moduleLabel.configure(
                    module.text(),
                    null,
                    owner.getFont().deriveFont(Font.PLAIN, Math.max(10f, owner.getFont().getSize2D() - 1f)),
                    selected,
                    foreground
            );
            moduleLabel.setToolTipText(module.tooltip());
            row.add(moduleLabel, BorderLayout.EAST);
            return row;
        }

    }
}
