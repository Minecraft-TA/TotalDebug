package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceUsage;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceUsagePage;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.search.reference.ReferenceSearchService;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.RuntimeModulePresentation;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import org.objectweb.asm.Type;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.border.CompoundBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;

public final class UsagesViewPanel extends JPanel {
    private static final String RESULTS_CARD = "results";
    private static final String MESSAGE_CARD = "message";
    private static final int INITIAL_RESULT_LIMIT = 200;
    private static final int MAX_RESULT_LIMIT = 5_000;

    private final ReferenceQuery query;
    private final String targetDisplayName;
    private final javax.swing.Icon targetIcon;
    private final ReferenceSearchService searchService;
    private final JLabel targetLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton showMoreButton = new JButton("Show more");
    private final JButton groupByButton = new JButton("Group by");
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(this.rootNode);
    private final JTree resultsTree = new JTree(this.treeModel);
    private final JLabel messageLabel = new JLabel("Looking up indexed references...", SwingConstants.CENTER);
    private final JPanel resultCards = new JPanel(new CardLayout());

    private ReferenceSearchService.SearchHandle activeSearch;
    private long searchGeneration;
    private boolean detached;
    private int resultLimit = INITIAL_RESULT_LIMIT;
    private boolean resultTruncated;
    private List<ReferenceUsage> currentUsages = List.of();
    private UsageTreeModel.Options groupingOptions = UsageTreeModel.Options.defaults();

    public UsagesViewPanel(CodeSymbol symbol, ReferenceSearchService searchService) {
        this(
                Objects.requireNonNull(symbol, "symbol").referenceQuery(),
                symbol.displayName(),
                symbolIcon(symbol),
                searchService
        );
    }

    public UsagesViewPanel(
            ReferenceQuery query,
            String targetDisplayName,
            javax.swing.Icon targetIcon,
            ReferenceSearchService searchService
    ) {
        super(new BorderLayout());
        this.query = Objects.requireNonNull(query, "query");
        this.targetDisplayName = Objects.requireNonNull(targetDisplayName, "targetDisplayName");
        this.targetIcon = Objects.requireNonNull(targetIcon, "targetIcon");
        this.searchService = Objects.requireNonNull(searchService, "searchService");

        configureHeader();
        configureResultsTree();
        this.resultCards.add(new JScrollPane(this.resultsTree), RESULTS_CARD);
        this.resultCards.add(this.messageLabel, MESSAGE_CARD);
        showCard(MESSAGE_CARD);
        add(this.resultCards, BorderLayout.CENTER);

        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0 && getParent() == null) {
                this.detached = true;
                cancelActiveSearch();
            }
        });
    }

    public void restartSearch() {
        requireEdt();
        this.detached = false;
        this.resultLimit = INITIAL_RESULT_LIMIT;
        this.resultTruncated = false;
        startSearch(true);
    }

    private void startSearch(boolean clearResults) {
        requireEdt();
        if (this.activeSearch != null) {
            this.activeSearch.cancel();
            this.activeSearch = null;
        }
        long generation = ++this.searchGeneration;
        if (clearResults) {
            this.currentUsages = List.of();
            this.rootNode.removeAllChildren();
            this.treeModel.reload();
            this.messageLabel.setText("Looking up indexed references...");
            showCard(MESSAGE_CARD);
        }
        if (this.resultLimit == INITIAL_RESULT_LIMIT) {
            this.statusLabel.setText("Searching indexed references");
        } else {
            this.statusLabel.setText("Loading up to " + this.resultLimit + " usage sites");
        }
        this.cancelButton.setVisible(true);
        this.cancelButton.setEnabled(true);
        this.showMoreButton.setVisible(false);

        this.activeSearch = this.searchService.search(
                this.query,
                this.resultLimit,
                new ReferenceSearchService.Listener() {
                    @Override
                    public void onCompleted(ReferenceUsagePage result) {
                        if (isCurrent(generation)) {
                            showResult(result);
                        }
                    }

                    @Override
                    public void onFailed(Throwable failure) {
                        if (isCurrent(generation)) {
                            showFailure(failure);
                        }
                    }
                }
        );
    }

    private void configureHeader() {
        this.targetLabel.setText("Usages of " + this.targetDisplayName);
        this.targetLabel.setIcon(this.targetIcon);

        Box firstRow = Box.createHorizontalBox();
        firstRow.add(this.targetLabel);
        firstRow.add(Box.createHorizontalGlue());
        firstRow.add(this.groupByButton);
        firstRow.add(Box.createHorizontalStrut(6));
        firstRow.add(this.showMoreButton);
        firstRow.add(Box.createHorizontalStrut(6));
        firstRow.add(this.cancelButton);

        JPanel header = new JPanel();
        header.setLayout(new javax.swing.BoxLayout(header, javax.swing.BoxLayout.Y_AXIS));
        header.add(firstRow);
        header.add(Box.createVerticalStrut(6));
        header.add(this.statusLabel);
        header.setBorder(new CompoundBorder(
                DynamicMatteBorder.separatorRule(0, 0, 1, 0),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        add(header, BorderLayout.NORTH);

        this.cancelButton.setVisible(false);
        this.showMoreButton.setVisible(false);
        this.groupByButton.setToolTipText("Choose how usage sites are grouped");
        this.groupByButton.addActionListener(event -> showGroupingMenu());
        this.cancelButton.addActionListener(event -> cancelActiveSearch());
        this.showMoreButton.addActionListener(event -> showMoreResults());
    }

    private void configureResultsTree() {
        this.resultsTree.setRootVisible(false);
        this.resultsTree.setShowsRootHandles(true);
        this.resultsTree.setCellRenderer(new UsageTreeCellRenderer());
        this.resultsTree.getSelectionModel().setSelectionMode(
                javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
        );
        ToolTipManager.sharedInstance().registerComponent(this.resultsTree);

        this.resultsTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2) {
                    openSelectedUsage();
                }
            }
        });
        this.resultsTree.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openUsage");
        this.resultsTree.getActionMap().put("openUsage", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                openSelectedUsage();
            }
        });
    }

    private void showResult(ReferenceUsagePage result) {
        requireEdt();
        this.activeSearch = null;
        this.cancelButton.setVisible(false);
        this.resultTruncated = result.truncated();
        this.currentUsages = result.usages();

        populateResults(this.currentUsages);
        int classCount = (int) result.usages().stream()
                .map(ReferenceUsage::location)
                .map(ReferenceLocation::className)
                .distinct()
                .count();
        long referenceCount = result.usages().stream().mapToLong(ReferenceUsage::occurrenceCount).sum();
        if (result.truncated()) {
            if (this.resultLimit < MAX_RESULT_LIMIT) {
                this.statusLabel.setText(
                        "Showing " + result.usages().size() + " usage sites (" + referenceCount
                                + " references) in " + classCount
                                + " classes. More are available."
                );
                this.showMoreButton.setVisible(true);
                this.showMoreButton.setEnabled(true);
            } else {
                this.statusLabel.setText(
                        "Showing " + result.usages().size() + " usage sites (" + referenceCount
                                + " references) in " + classCount
                                + " classes. Additional sites are omitted."
                );
                this.showMoreButton.setVisible(false);
            }
        } else {
            this.statusLabel.setText(result.usages().size() + " usage sites (" + referenceCount
                    + " references) in " + classCount + " classes");
            this.showMoreButton.setVisible(false);
        }

        if (result.usages().isEmpty()) {
            this.messageLabel.setText("No usages found");
            showCard(MESSAGE_CARD);
        } else {
            showCard(RESULTS_CARD);
        }
    }

    private void showFailure(Throwable failure) {
        requireEdt();
        this.activeSearch = null;
        this.cancelButton.setVisible(false);
        this.showMoreButton.setVisible(false);
        String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        this.statusLabel.setText("Reference search failed");
        this.messageLabel.setText("Unable to query the reference index: " + detail);
        showCard(MESSAGE_CARD);
        failure.printStackTrace(System.err);
    }

    private void populateResults(List<ReferenceUsage> usages) {
        this.rootNode.removeAllChildren();
        if (!usages.isEmpty()) {
            UsageTreeModel.Group grouped = UsageTreeModel.build(
                    usages,
                    this.searchService.sourceCatalog(),
                    this.groupingOptions
            );
            this.rootNode.add(createTreeNode(grouped));
        }
        this.treeModel.reload();
        for (int row = 0; row < this.resultsTree.getRowCount(); row++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) this.resultsTree.getPathForRow(row)
                    .getLastPathComponent();
            if (node.getUserObject() instanceof GroupNode) {
                this.resultsTree.expandRow(row);
            }
        }
    }

    private DefaultMutableTreeNode createTreeNode(UsageTreeModel.Group group) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(new GroupNode(group));
        for (UsageTreeModel.Group child : group.children()) {
            node.add(createTreeNode(child));
        }
        boolean includeClassName = !this.groupingOptions.fileStructure();
        for (ReferenceUsage usage : group.usages()) {
            node.add(new DefaultMutableTreeNode(new UsageNode(
                    usage,
                    includeClassName,
                    RuntimeModulePresentation.of(
                            this.searchService.sourceCatalog().sourceFor(usage.sourceId())
                    ).tooltip()
            )));
        }
        return node;
    }

    private void showGroupingMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem heading = new JMenuItem("Group by");
        heading.setEnabled(false);
        menu.add(heading);
        menu.addSeparator();
        menu.add(groupingItem("File structure", this.groupingOptions.fileStructure(), selected ->
                updateGrouping(new UsageTreeModel.Options(
                        this.groupingOptions.module(),
                        this.groupingOptions.packageName(),
                        selected,
                        this.groupingOptions.usageType()
                ))));
        menu.add(groupingItem("Module", this.groupingOptions.module(), selected ->
                updateGrouping(new UsageTreeModel.Options(
                        selected,
                        this.groupingOptions.packageName(),
                        this.groupingOptions.fileStructure(),
                        this.groupingOptions.usageType()
                ))));
        menu.add(groupingItem("Package", this.groupingOptions.packageName(), selected ->
                updateGrouping(new UsageTreeModel.Options(
                        this.groupingOptions.module(),
                        selected,
                        this.groupingOptions.fileStructure(),
                        this.groupingOptions.usageType()
                ))));
        menu.add(groupingItem("Usage type", this.groupingOptions.usageType(), selected ->
                updateGrouping(new UsageTreeModel.Options(
                        this.groupingOptions.module(),
                        this.groupingOptions.packageName(),
                        this.groupingOptions.fileStructure(),
                        selected
                ))));
        menu.show(
                this.groupByButton,
                Math.max(0, this.groupByButton.getWidth() - menu.getPreferredSize().width),
                this.groupByButton.getHeight()
        );
    }

    private JCheckBoxMenuItem groupingItem(
            String label,
            boolean selected,
            java.util.function.Consumer<Boolean> selection
    ) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(label, selected);
        item.addActionListener(event -> selection.accept(item.isSelected()));
        return item;
    }

    private void updateGrouping(UsageTreeModel.Options options) {
        this.groupingOptions = options;
        populateResults(this.currentUsages);
    }

    private void openSelectedUsage() {
        TreePath selection = this.resultsTree.getSelectionPath();
        if (selection == null) {
            return;
        }
        Object nodeValue = ((DefaultMutableTreeNode) selection.getLastPathComponent()).getUserObject();
        if (!(nodeValue instanceof UsageNode usage)) {
            return;
        }
        CompanionApp.getDecompilationService().openUsage(usage.usage(), this.query);
    }

    private void cancelActiveSearch() {
        if (this.activeSearch != null && !this.activeSearch.isDone()) {
            this.activeSearch.cancel();
            this.activeSearch = null;
            this.searchGeneration++;
            this.statusLabel.setText("Search cancelled");
            this.cancelButton.setVisible(false);
            this.showMoreButton.setVisible(this.resultTruncated);
        }
    }

    private void showMoreResults() {
        requireEdt();
        if (this.activeSearch != null || !this.resultTruncated) {
            return;
        }
        this.resultLimit = nextResultLimit(this.resultLimit);
        startSearch(false);
    }

    private static int nextResultLimit(int current) {
        return Math.min(MAX_RESULT_LIMIT, current * 5);
    }

    private boolean isCurrent(long generation) {
        requireEdt();
        return !this.detached && generation == this.searchGeneration;
    }

    private void showCard(String card) {
        ((CardLayout) this.resultCards.getLayout()).show(this.resultCards, card);
    }

    private static javax.swing.Icon symbolIcon(CodeSymbol symbol) {
        return switch (symbol) {
            case CodeSymbol.ClassSymbol ignored -> Icons.JAVA_CLASS;
            case CodeSymbol.FieldSymbol ignored -> Icons.FIELD;
            case CodeSymbol.MethodSymbol method -> "<init>".equals(method.name())
                    ? Icons.JAVA_CONSTRUCTOR
                    : Icons.JAVA_METHOD;
        };
    }

    private static javax.swing.Icon locationIcon(ReferenceLocation.Site site) {
        return switch (site) {
            case ReferenceLocation.ClassDeclaration ignored -> Icons.JAVA_CLASS;
            case ReferenceLocation.Field ignored -> Icons.FIELD;
            case ReferenceLocation.RecordComponent ignored -> Icons.FIELD;
            case ReferenceLocation.Method method -> "<init>".equals(method.name())
                    ? Icons.JAVA_CONSTRUCTOR
                    : Icons.JAVA_METHOD;
        };
    }

    private static String usageLabel(ReferenceLocation.Site site) {
        return switch (site) {
            case ReferenceLocation.ClassDeclaration ignored -> "class declaration";
            case ReferenceLocation.Field field -> field.name() + " : " + simpleType(field.descriptor());
            case ReferenceLocation.RecordComponent component ->
                    component.name() + " : " + simpleType(component.descriptor()) + " (record component)";
            case ReferenceLocation.Method method -> methodLabel(method);
        };
    }

    private static String usageLabel(ReferenceUsage usage, boolean includeClassName) {
        String relations = usage.kinds().stream()
                .sorted()
                .map(UsageTreeModel::relationLabel)
                .collect(java.util.stream.Collectors.joining(", "));
        String count = usage.occurrenceCount() == 1 ? "" : " ×" + usage.occurrenceCount();
        String owner = includeClassName
                ? UsageTreeModel.simpleClassName(usage.location().className()) + '.'
                : "";
        return owner + usageLabel(usage.location().site()) + "  -  " + relations + count;
    }

    private static String methodLabel(ReferenceLocation.Method method) {
        Type methodType = Type.getMethodType(method.descriptor());
        String arguments = java.util.Arrays.stream(methodType.getArgumentTypes())
                .map(Type::getClassName)
                .map(UsagesViewPanel::simpleTypeName)
                .collect(java.util.stream.Collectors.joining(", "));
        return method.name() + '(' + arguments + ')';
    }

    private static String simpleType(String descriptor) {
        return simpleTypeName(Type.getType(descriptor).getClassName());
    }

    private static String simpleTypeName(String className) {
        int packageSeparator = className.lastIndexOf('.');
        return className.substring(packageSeparator + 1).replace('$', '.');
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Usages UI must be updated on the Swing event thread");
        }
    }

    private record GroupNode(UsageTreeModel.Group group) {
        @Override
        public String toString() {
            String result = this.group.siteCount() == 1 ? "1 result" : this.group.siteCount() + " results";
            return this.group.label() + "  " + result;
        }
    }

    private record UsageNode(ReferenceUsage usage, boolean includeClassName, String moduleTooltip) {
        @Override
        public String toString() {
            return usageLabel(this.usage, this.includeClassName);
        }
    }

    private static final class UsageTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(
                JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus
        ) {
            Component component = super.getTreeCellRendererComponent(
                    tree, value, selected, expanded, leaf, row, hasFocus
            );
            Object userValue = ((DefaultMutableTreeNode) value).getUserObject();
            if (userValue instanceof GroupNode group) {
                setIcon(switch (group.group().kind()) {
                    case SCOPE, USAGE_TYPE -> Icons.SEARCH_ICON;
                    case MODULE -> Icons.MODULE;
                    case PACKAGE -> Icons.PACKAGE;
                    case CLASS -> Icons.JAVA_CLASS;
                });
                setToolTipText(group.group().referenceCount() + " indexed references");
            } else if (userValue instanceof UsageNode usage) {
                ReferenceLocation location = usage.usage().location();
                setIcon(locationIcon(location.site()));
                setToolTipText(
                        usage.moduleTooltip() + " | " + location.className() + '#'
                                + usageLabel(usage.usage(), false)
                );
            }
            return component;
        }
    }
}
