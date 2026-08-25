package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.model.LiteralUsagesView;
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
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.util.DocumentChangeListener;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.jindex.SymbolKind;
import org.eclipse.jdt.core.IJavaElement;

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
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    private final FlatIconTextField searchTextField = new FlatIconTextField(Icons.SEARCH_ICON);
    private final JButton moduleFilterButton = new JButton(Icons.FILTER);
    private final Map<Category, JToggleButton> categoryButtons = new LinkedHashMap<>();
    private final LinkedHashSet<String> selectedModuleIds = new LinkedHashSet<>();
    private final ModuleFilterPopup moduleFilterPopup;

    private RuntimeSourceCatalog sourceCatalog = RuntimeSourceCatalog.empty();
    private List<RuntimeInventory.RuntimeModule> modules = List.of();
    private Category category = Category.ALL;
    private ScheduledFuture<?> pendingSearch;

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
        UIUtils.centerJFrame(this);
        this.searchTextField.requestFocusInWindow();
        this.searchTextField.selectAll();
        refreshResults();
    }

    @Override
    public void dispose() {
        this.searchGeneration.incrementAndGet();
        if (this.pendingSearch != null) {
            this.pendingSearch.cancel(false);
        }
        this.searchExecutor.shutdownNow();
        super.dispose();
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 6));
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel tabs = new JPanel();
        tabs.setLayout(new BoxLayout(tabs, BoxLayout.X_AXIS));
        ButtonGroup group = new ButtonGroup();
        for (Category candidate : Category.values()) {
            JToggleButton button = new JToggleButton(candidate.label(), candidate == this.category);
            button.putClientProperty("JButton.buttonType", "toolBarButton");
            button.setFocusable(false);
            button.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            button.addActionListener(event -> selectCategory(candidate));
            group.add(button);
            tabs.add(button);
            tabs.add(Box.createHorizontalStrut(2));
            this.categoryButtons.put(candidate, button);
        }
        tabs.add(Box.createHorizontalGlue());
        tabs.add(this.moduleFilterButton);

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
        this.resultCount.setForeground(ThemeColors.mutedText());
        JLabel shortcuts = new JLabel("↑↓ Navigate    Enter Open    Esc Close");
        shortcuts.setForeground(ThemeColors.mutedText());
        footer.add(this.resultCount, BorderLayout.WEST);
        footer.add(shortcuts, BorderLayout.EAST);
        return footer;
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
        this.searchTextField.getDocument().addDocumentListener((DocumentChangeListener) event -> refreshResults());
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
            this.resultModel.clear();
            this.resultCount.setText(" ");
            showMessage(this.category == Category.TEXT
                    ? "Type to search indexed string literals"
                    : "Type to search classes and symbols");
            return;
        }
        if (this.category != Category.TEXT && !query.chars().allMatch(character -> character < 128)) {
            this.resultModel.clear();
            this.resultCount.setText("0 results");
            showMessage("Class and symbol names are indexed as JVM ASCII names");
            return;
        }

        Category requestedCategory = this.category;
        int[] sourceIds = this.selectedModuleIds.size() == this.modules.size()
                ? null
                : this.sourceCatalog.sourceIdsForModules(this.selectedModuleIds);
        showMessage("Searching the runtime index...");
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
        failure.printStackTrace(System.err);
        this.resultModel.clear();
        this.resultCount.setText("Search failed");
        showMessage("Unable to query the runtime index: " + failure.getMessage());
    }

    private void showIndexStatus(RuntimeIndexService.Status status) {
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
        Result selected = this.resultList.getSelectedValue();
        if (selected == null) {
            return;
        }
        setVisible(false);
        switch (selected) {
            case ClassResult type -> CompanionApp.openClass(type.binaryName());
            case SymbolResult symbol -> CompanionApp.openClass(
                    symbol.ownerBinaryName(),
                    symbol.kind() == SymbolKind.FIELD ? IJavaElement.FIELD : IJavaElement.METHOD,
                    symbol.kind() == SymbolKind.FIELD ? symbol.name() : symbol.name() + symbol.descriptor()
            );
            case TextResult text -> MainWindow.INSTANCE.getEditorTabs().focusOrCreateIfAbsent(
                    LiteralUsagesView.class,
                    view -> view.literal().equals(text.value()),
                    () -> new LiteralUsagesView(text.value())
            ).thenAccept(LiteralUsagesView::restartSearch);
        }
    }

    private String moduleLabel(Result result) {
        List<String> labels = Arrays.stream(result.sourceIds())
                .mapToObj(this.sourceCatalog::moduleFor)
                .distinct()
                .map(RuntimeInventory.RuntimeModule::displayName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        if (labels.size() <= 2) {
            return String.join(", ", labels);
        }
        return labels.get(0) + ", " + labels.get(1) + " +" + (labels.size() - 2);
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

            JLabel primary = new JLabel();
            primary.setForeground(foreground);
            primary.setFont(primary.getFont().deriveFont(Font.PLAIN));
            JLabel secondary = new JLabel();
            secondary.setForeground(selected ? foreground : ThemeColors.mutedText());

            Icon icon;
            switch (value) {
                case ClassResult type -> {
                    primary.setText(type.simpleName());
                    secondary.setText(type.packageName());
                    icon = type.isInterface() ? Icons.JAVA_INTERFACE
                            : type.isEnum() ? Icons.JAVA_ENUM : Icons.JAVA_CLASS;
                }
                case SymbolResult symbol -> {
                    primary.setText(symbolPrimary(symbol));
                    String owner = simpleClassName(symbol.ownerBinaryName());
                    String ownerPackage = packageName(symbol.ownerBinaryName());
                    secondary.setText("in " + owner + (ownerPackage.isEmpty() ? "" : "  " + ownerPackage));
                    icon = symbol.kind() == SymbolKind.FIELD ? Icons.FIELD
                            : "<init>".equals(symbol.name()) ? Icons.JAVA_CONSTRUCTOR : Icons.JAVA_METHOD;
                }
                case TextResult text -> {
                    primary.setText(textPreview(text.value()));
                    primary.setToolTipText(text.value());
                    secondary.setText("String literal");
                    icon = Icons.VALUE;
                }
            }

            JPanel left = new JPanel();
            left.setOpaque(false);
            left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
            primary.setIcon(icon);
            primary.setIconTextGap(7);
            left.add(primary);
            left.add(Box.createHorizontalStrut(8));
            left.add(secondary);

            JLabel module = new JLabel(moduleLabel(value), SwingConstants.RIGHT);
            module.setForeground(selected ? foreground : ThemeColors.mutedText());
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
}
