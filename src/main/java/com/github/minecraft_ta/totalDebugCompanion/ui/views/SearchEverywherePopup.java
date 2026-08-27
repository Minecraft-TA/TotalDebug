package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.RuntimeMember;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeInventory;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.Category;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.ClassResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.Result;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.SymbolResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.TextResult;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconTextField;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryText;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.RuntimeModulePresentation;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.jindex.SymbolKind;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SearchEverywherePopup extends JFrame {
    private static final int RESULT_LIMIT = 160;
    private static final String RESULTS_CARD = "results";
    private static final String MESSAGE_CARD = "message";

    private final SearchEverywhereSearch search = new SearchEverywhereSearch();
    private final ScheduledExecutorService searchExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform()
            .daemon(true)
            .name("totaldebug-search-everywhere")
            .unstarted(runnable));
    private final AtomicLong searchGeneration = new AtomicLong();
    private final DefaultListModel<Result> resultModel = new DefaultListModel<>();
    private final JList<Result> resultList = new JList<>(this.resultModel);
    private final JScrollPane resultScrollPane = new JScrollPane(this.resultList);
    private final JLabel messageLabel = new JLabel("Type to search the runtime index", SwingConstants.CENTER);
    private final JPanel resultCards = new JPanel(new CardLayout());
    private final JLabel resultCount = new JLabel(" ");
    private final JLabel shortcutsLabel = new JLabel("↑↓ Navigate    Enter Open    Esc Close");
    private final FlatIconTextField searchTextField = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JButton moduleFilterButton = new JButton(Icons.FILTER);
    private final Map<Category, JToggleButton> categoryButtons = new LinkedHashMap<>();
    private final LinkedHashSet<String> selectedModuleIds = new LinkedHashSet<>();
    private final ModuleFilterPopup moduleFilterPopup;
    private final Consumer<CompanionTheme> themeListener = theme -> applyTheme();

    private RuntimeSourceCatalog sourceCatalog = RuntimeSourceCatalog.empty();
    private List<RuntimeInventory.RuntimeModule> modules = List.of();
    private Category category = Category.ALL;
    private ScheduledFuture<?> pendingSearch;
    private boolean searchPending;
    private boolean documentRefreshQueued;
    private Point dragPointerOrigin;
    private Point dragWindowOrigin;
    private boolean manuallyPositioned;

    SearchEverywherePopup() {
        this.moduleFilterPopup = new ModuleFilterPopup(
                this.modules,
                this.selectedModuleIds,
                this::setSelectedModules
        );
        configureResultList();
        configureSearchField();
        configureFilterButton();

        setLayout(new BorderLayout());
        add(createHeader(), BorderLayout.NORTH);
        this.resultCards.add(this.resultScrollPane, RESULTS_CARD);
        this.resultCards.add(this.messageLabel, MESSAGE_CARD);
        add(this.resultCards, BorderLayout.CENTER);
        add(createFooter(), BorderLayout.SOUTH);
        applyTheme();
        ThemeManager.addThemeChangeListener(this.themeListener);

        getRootPane().registerKeyboardAction(
                event -> setVisible(false),
                KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        getRootPane().registerKeyboardAction(
                event -> openSelectedResult(),
                KeyStroke.getKeyStroke("ENTER"),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        CompanionApp.addRuntimeIndexStatusListener(status -> SwingUtilities.invokeLater(() -> {
            if (CompanionClassIndex.isOpen()) {
                syncRuntimeModules();
                refreshResults();
            } else {
                showIndexStatus(status);
            }
        }));

        ((JPanel) getContentPane()).setBorder(DynamicMatteBorder.rule(1, 1, 1, 1));
        setUndecorated(true);
        pack();
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent event) {
                if (!moduleFilterPopup.isVisible()) {
                    setVisible(false);
                }
            }
        });
    }

    void open() {
        if (isVisible()) {
            toFront();
            return;
        }
        if (CompanionClassIndex.isOpen()) {
            syncRuntimeModules();
        }
        setVisible(true);
        if (!this.manuallyPositioned) {
            UIUtils.centerJFrame(this);
        }
        this.searchTextField.requestFocusInWindow();
        this.searchTextField.selectAll();
        refreshResults();
    }

    void open(Set<String> moduleIds, String query) {
        Objects.requireNonNull(moduleIds, "moduleIds");
        Objects.requireNonNull(query, "query");
        if (CompanionClassIndex.isOpen()) {
            syncRuntimeModules();
        }
        setSelectedModules(moduleIds);
        this.searchTextField.setText(query);
        open();
    }

    @Override
    public void dispose() {
        ThemeManager.removeThemeChangeListener(this.themeListener);
        this.searchGeneration.incrementAndGet();
        if (this.pendingSearch != null) {
            this.pendingSearch.cancel(false);
        }
        this.searchExecutor.shutdownNow();
        super.dispose();
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 6));
        header.setName("searchEverywhere.header");
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel tabs = new JPanel();
        tabs.setName("searchEverywhere.dragSurface");
        tabs.setLayout(new BoxLayout(tabs, BoxLayout.X_AXIS));
        ButtonGroup group = new ButtonGroup();
        for (Category candidate : Category.values()) {
            JToggleButton button = new SearchCategoryButton(candidate.label(), candidate == this.category);
            button.setName("searchEverywhere.category." + candidate.name().toLowerCase(java.util.Locale.ROOT));
            button.addActionListener(event -> selectCategory(candidate));
            group.add(button);
            tabs.add(button);
            tabs.add(Box.createHorizontalStrut(2));
            this.categoryButtons.put(candidate, button);
        }
        tabs.add(Box.createHorizontalGlue());
        tabs.add(this.moduleFilterButton);
        installDragSupport(header);
        installDragSupport(tabs);

        header.add(tabs, BorderLayout.NORTH);
        header.add(this.searchTextField, BorderLayout.CENTER);
        return header;
    }

    private JPanel createFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(new javax.swing.border.CompoundBorder(
                DynamicMatteBorder.rule(1, 0, 0, 0),
                BorderFactory.createEmptyBorder(5, 9, 5, 9)
        ));
        footer.add(this.resultCount, BorderLayout.WEST);
        footer.add(this.shortcutsLabel, BorderLayout.EAST);
        return footer;
    }

    private void applyTheme() {
        this.resultCount.setForeground(ThemeColors.mutedText());
        this.shortcutsLabel.setForeground(ThemeColors.mutedText());
        this.resultList.repaint();
    }

    private void configureResultList() {
        this.resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.resultList.setCellRenderer(new SearchResultRenderer());
        this.resultList.setFixedCellHeight(32);
        this.resultList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }
            int selected = this.resultList.getSelectedIndex();
            if (selected >= 0) {
                this.resultList.ensureIndexIsVisible(selected);
            }
        });
        this.resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() < 2 || !SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                int index = resultList.locationToIndex(event.getPoint());
                if (index >= 0 && resultList.getCellBounds(index, index).contains(event.getPoint())) {
                    resultList.setSelectedIndex(index);
                    openSelectedResult();
                }
            }
        });
        this.resultScrollPane.setPreferredSize(new Dimension(820, 470));
        this.resultScrollPane.setBorder(DynamicMatteBorder.rule(1, 0, 0, 0));
    }

    private void configureSearchField() {
        this.searchTextField.putClientProperty("JTextField.placeholderText", "Search classes and symbols");
        this.searchTextField.getDocument().addDocumentListener((DocumentChangeListener) event -> queueDocumentRefresh());
        this.searchTextField.registerKeyboardAction(
                event -> moveSelection(-1),
                KeyStroke.getKeyStroke("UP"),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        this.searchTextField.registerKeyboardAction(
                event -> moveSelection(1),
                KeyStroke.getKeyStroke("DOWN"),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
    }

    private void queueDocumentRefresh() {
        if (this.documentRefreshQueued) {
            return;
        }
        this.documentRefreshQueued = true;
        SwingUtilities.invokeLater(() -> {
            this.documentRefreshQueued = false;
            refreshResults();
        });
    }

    private void configureFilterButton() {
        this.moduleFilterButton.putClientProperty("JButton.buttonType", "toolBarButton");
        this.moduleFilterButton.setFocusable(false);
        this.moduleFilterButton.setHorizontalTextPosition(SwingConstants.LEFT);
        this.moduleFilterButton.setIconTextGap(6);
        this.moduleFilterButton.setToolTipText("Filter search results by module");
        this.moduleFilterButton.addActionListener(event -> {
            syncRuntimeModules();
            this.moduleFilterPopup.updateModules(this.modules, this.selectedModuleIds);
            this.moduleFilterPopup.show(
                    this.moduleFilterButton,
                    this.moduleFilterButton.getWidth() - this.moduleFilterPopup.getPreferredSize().width,
                    this.moduleFilterButton.getHeight()
            );
            SwingUtilities.invokeLater(this.moduleFilterPopup::focusSearch);
        });
        updateFilterButton();
    }

    private void installDragSupport(JComponent surface) {
        MouseAdapter drag = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                dragPointerOrigin = event.getLocationOnScreen();
                dragWindowOrigin = getLocation();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragPointerOrigin == null
                        || dragWindowOrigin == null
                        || (event.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 0) {
                    return;
                }
                Point pointer = event.getLocationOnScreen();
                setLocation(
                        dragWindowOrigin.x + pointer.x - dragPointerOrigin.x,
                        dragWindowOrigin.y + pointer.y - dragPointerOrigin.y
                );
                manuallyPositioned = true;
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dragPointerOrigin = null;
                dragWindowOrigin = null;
            }
        };
        surface.addMouseListener(drag);
        surface.addMouseMotionListener(drag);
    }

    private void selectCategory(Category selected) {
        this.category = selected;
        this.categoryButtons.get(selected).setSelected(true);
        this.searchTextField.putClientProperty(
                "JTextField.placeholderText",
                selected == Category.TEXT ? "Search indexed string literals" : "Search classes and symbols"
        );
        refreshResults();
    }

    private void setSelectedModules(Set<String> moduleIds) {
        this.selectedModuleIds.clear();
        this.selectedModuleIds.addAll(moduleIds);
        updateFilterButton();
        refreshResults();
    }

    private void syncRuntimeModules() {
        RuntimeSourceCatalog currentCatalog = CompanionApp.getReferenceSearchService().sourceCatalog();
        List<RuntimeInventory.RuntimeModule> currentModules = currentCatalog.modules();
        if (!currentModules.equals(this.modules)) {
            this.sourceCatalog = currentCatalog;
            this.modules = currentModules;
            this.selectedModuleIds.clear();
            currentModules.stream().map(RuntimeInventory.RuntimeModule::id).forEach(this.selectedModuleIds::add);
            this.moduleFilterPopup.updateModules(this.modules, this.selectedModuleIds);
            updateFilterButton();
        } else {
            this.sourceCatalog = currentCatalog;
        }
    }

    private void updateFilterButton() {
        int selected = this.selectedModuleIds.size();
        if (this.modules.isEmpty() || selected == this.modules.size()) {
            this.moduleFilterButton.setText("All modules");
        } else if (selected == 0) {
            this.moduleFilterButton.setText("No modules");
        } else {
            this.moduleFilterButton.setText(selected + " modules");
        }
    }

    private void moveSelection(int delta) {
        int size = this.resultModel.size();
        if (size == 0) {
            return;
        }
        int selected = this.resultList.getSelectedIndex();
        this.resultList.setSelectedIndex(Math.max(0, Math.min(size - 1, selected + delta)));
    }

    private void refreshResults() {
        long generation = this.searchGeneration.incrementAndGet();
        if (this.pendingSearch != null) {
            this.pendingSearch.cancel(false);
            this.pendingSearch = null;
        }
        if (!CompanionClassIndex.isOpen()) {
            showIndexStatus(CompanionApp.getRuntimeIndexStatus());
            return;
        }

        String query = this.searchTextField.getText().strip();
        if (query.isEmpty()) {
            this.searchPending = false;
            this.resultModel.clear();
            this.resultCount.setText(" ");
            showMessage(this.category == Category.TEXT
                    ? "Type to search indexed string literals"
                    : "Type to search classes and symbols");
            return;
        }
        if (this.category != Category.TEXT && !query.chars().allMatch(character -> character < 128)) {
            this.searchPending = false;
            this.resultModel.clear();
            this.resultCount.setText("0 results");
            showMessage("Class and symbol names are indexed as JVM ASCII names");
            return;
        }

        Category requestedCategory = this.category;
        int[] sourceIds = this.selectedModuleIds.size() == this.modules.size()
                ? null
                : this.sourceCatalog.sourceIdsForModules(this.selectedModuleIds);
        this.searchPending = true;
        this.resultCount.setText("Searching…");
        this.pendingSearch = this.searchExecutor.schedule(() -> {
            try {
                List<Result> results = this.search.search(
                        CompanionClassIndex.get(),
                        query,
                        requestedCategory,
                        RESULT_LIMIT,
                        sourceIds
                );
                SwingUtilities.invokeLater(() -> applyResults(generation, results));
            } catch (RuntimeException failure) {
                SwingUtilities.invokeLater(() -> showSearchFailure(generation, failure));
            }
        }, 70, TimeUnit.MILLISECONDS);
    }

    private void applyResults(long generation, List<Result> results) {
        if (generation != this.searchGeneration.get()) {
            return;
        }
        this.pendingSearch = null;
        this.searchPending = false;
        String selectedIdentity = this.resultList.getSelectedValue() == null
                ? null
                : identity(this.resultList.getSelectedValue());
        this.resultModel.clear();
        this.resultModel.addAll(results);
        this.resultCount.setText(results.size() + (results.size() == 1 ? " result" : " results"));
        if (results.isEmpty()) {
            showMessage("No results in the selected modules");
            return;
        }
        ((CardLayout) this.resultCards.getLayout()).show(this.resultCards, RESULTS_CARD);
        int selectedIndex = 0;
        if (selectedIdentity != null) {
            for (int index = 0; index < results.size(); index++) {
                if (identity(results.get(index)).equals(selectedIdentity)) {
                    selectedIndex = index;
                    break;
                }
            }
        }
        this.resultList.setSelectedIndex(selectedIndex);
    }

    private void showSearchFailure(long generation, RuntimeException failure) {
        if (generation != this.searchGeneration.get()) {
            return;
        }
        this.pendingSearch = null;
        this.searchPending = false;
        failure.printStackTrace(System.err);
        this.resultModel.clear();
        this.resultCount.setText("Search failed");
        showMessage("Unable to query the runtime index: " + failure.getMessage());
    }

    private void showIndexStatus(RuntimeIndexService.Status status) {
        this.searchPending = false;
        this.resultModel.clear();
        this.resultCount.setText("Index unavailable");
        showMessage(status.phase() == RuntimeIndexService.Phase.FAILED
                ? "Runtime index unavailable — use Retry in the bottom bar"
                : "Building runtime index... " + status.detail());
    }

    private void showMessage(String message) {
        this.messageLabel.setText(message);
        ((CardLayout) this.resultCards.getLayout()).show(this.resultCards, MESSAGE_CARD);
    }

    private void openSelectedResult() {
        if (this.searchPending) {
            return;
        }
        Result selected = this.resultList.getSelectedValue();
        if (selected == null) {
            return;
        }
        setVisible(false);
        switch (selected) {
            case ClassResult type -> MainWindow.INSTANCE.navigation().navigate(
                    new NavigationTarget.RuntimeClass(type.binaryName())
            );
            case SymbolResult symbol -> MainWindow.INSTANCE.navigation().navigate(
                    new NavigationTarget.RuntimeDeclaration(symbol.kind() == SymbolKind.FIELD
                            ? new RuntimeMember.Field(symbol.ownerBinaryName(), symbol.name())
                            : new RuntimeMember.Method(
                                    symbol.ownerBinaryName(),
                                    symbol.name(),
                                    symbol.descriptor()
                            ))
            );
            case TextResult text -> MainWindow.INSTANCE.navigation().navigate(
                    new NavigationTarget.LiteralUsages(text.value())
            );
        }
    }

    private ModuleSummary moduleSummary(Result result) {
        var sources = Arrays.stream(result.sourceIds())
                .mapToObj(this.sourceCatalog::sourceFor)
                .distinct()
                .toList();
        List<RuntimeInventory.RuntimeModule> resultModules = sources.stream()
                .map(com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource.Source::module)
                .distinct()
                .toList();
        PrimarySecondaryText text = resultModules.size() == 1
                ? RuntimeModulePresentation.of(resultModules.getFirst()).text()
                : PrimarySecondaryText.primary(RuntimeModulePresentation.compactSummary(resultModules));
        String tooltip = sources.stream()
                .map(RuntimeModulePresentation::of)
                .map(RuntimeModulePresentation::tooltip)
                .distinct()
                .collect(Collectors.joining(" | "));
        return new ModuleSummary(text, tooltip);
    }

    private static String identity(Result result) {
        return switch (result) {
            case ClassResult type -> "C:" + type.binaryName();
            case SymbolResult symbol -> "S:" + symbol.ownerBinaryName() + '#' + symbol.name() + symbol.descriptor();
            case TextResult text -> "T:" + text.value();
        };
    }

    private static String symbolPrimary(SymbolResult symbol) {
        if (symbol.kind() == SymbolKind.FIELD) {
            return symbol.name() + " : " + simpleType(org.objectweb.asm.Type.getType(symbol.descriptor()));
        }
        String name = "<init>".equals(symbol.name())
                ? simpleClassName(symbol.ownerBinaryName())
                : symbol.name();
        return name + Arrays.stream(org.objectweb.asm.Type.getArgumentTypes(symbol.descriptor()))
                .map(SearchEverywherePopup::simpleType)
                .collect(Collectors.joining(", ", "(", ")"));
    }

    private static String simpleType(org.objectweb.asm.Type type) {
        String className = type.getClassName();
        int separator = className.lastIndexOf('.');
        return separator < 0 ? className : className.substring(separator + 1);
    }

    private static String simpleClassName(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1).replace('$', '.');
    }

    private static String packageName(String binaryName) {
        int separator = binaryName.lastIndexOf('.');
        return separator < 0 ? "" : binaryName.substring(0, separator);
    }

    private static String textPreview(String value) {
        String preview = value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
        if (preview.length() > 100) {
            preview = preview.substring(0, 99) + '…';
        }
        return '"' + preview + '"';
    }

    private final class SearchResultRenderer implements ListCellRenderer<Result> {
        @Override
        public Component getListCellRendererComponent(
                JList<? extends Result> list,
                Result value,
                int index,
                boolean selected,
                boolean focused
        ) {
            Color background = selected ? list.getSelectionBackground() : list.getBackground();
            Color foreground = selected ? list.getSelectionForeground() : ThemeColors.text();

            PrimarySecondaryText presentation;
            Icon icon;
            switch (value) {
                case ClassResult type -> {
                    presentation = new PrimarySecondaryText(type.simpleName(), type.packageName());
                    icon = type.isInterface() ? Icons.JAVA_INTERFACE
                            : type.isEnum() ? Icons.JAVA_ENUM : Icons.JAVA_CLASS;
                }
                case SymbolResult symbol -> {
                    String owner = simpleClassName(symbol.ownerBinaryName());
                    String ownerPackage = packageName(symbol.ownerBinaryName());
                    presentation = new PrimarySecondaryText(
                            symbolPrimary(symbol),
                            "in " + owner + (ownerPackage.isEmpty() ? "" : "  " + ownerPackage)
                    );
                    icon = symbol.kind() == SymbolKind.FIELD ? Icons.FIELD
                            : "<init>".equals(symbol.name()) ? Icons.JAVA_CONSTRUCTOR : Icons.JAVA_METHOD;
                }
                case TextResult text -> {
                    presentation = new PrimarySecondaryText(textPreview(text.value()), "String literal");
                    icon = Icons.VALUE;
                }
            }

            PrimarySecondaryLabel left = new PrimarySecondaryLabel();
            left.configure(presentation, icon, list.getFont(), selected, foreground);
            if (value instanceof TextResult text) {
                left.setToolTipText(text.value());
            }

            ModuleSummary moduleSummary = moduleSummary(value);
            PrimarySecondaryLabel module = new PrimarySecondaryLabel();
            module.configure(moduleSummary.text(), null, list.getFont(), selected, foreground);
            module.setToolTipText(moduleSummary.tooltip());
            module.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));

            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(true);
            row.setBackground(background);
            row.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
            row.add(left, BorderLayout.CENTER);
            row.add(module, BorderLayout.EAST);
            return row;
        }
    }

    private record ModuleSummary(PrimarySecondaryText text, String tooltip) {
    }

    private static final class SearchCategoryButton extends JToggleButton {
        private SearchCategoryButton(String text, boolean selected) {
            super(text, selected);
            setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusable(false);
            setOpaque(false);
            setRolloverEnabled(true);
        }

        @Override
        public Color getForeground() {
            if (isSelected()) {
                Color selectedForeground = UIManager.getColor("List.selectionForeground");
                return selectedForeground == null ? Color.WHITE : selectedForeground;
            }
            return ThemeColors.text();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            ButtonModel model = getModel();
            if (model.isSelected() || model.isRollover()) {
                Graphics2D paint = (Graphics2D) graphics.create();
                paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                paint.setColor(model.isSelected() ? ThemeColors.accent() : ThemeColors.hoverBackground());
                paint.fillRoundRect(0, 1, getWidth(), Math.max(0, getHeight() - 2), 7, 7);
                paint.dispose();
            }
            super.paintComponent(graphics);
        }
    }
}
