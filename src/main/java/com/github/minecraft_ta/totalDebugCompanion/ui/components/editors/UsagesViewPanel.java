package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocationPage;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.search.reference.ReferenceNavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.search.reference.ReferenceSearchService;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import org.objectweb.asm.Type;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class UsagesViewPanel extends JPanel {
    private static final String RESULTS_CARD = "results";
    private static final String MESSAGE_CARD = "message";
    private static final int INITIAL_RESULT_LIMIT = 200;
    private static final int MAX_RESULT_LIMIT = 5_000;

    private final CodeSymbol symbol;
    private final ReferenceSearchService searchService;
    private final JLabel targetLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton showMoreButton = new JButton("Show more");
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

    public UsagesViewPanel(CodeSymbol symbol, ReferenceSearchService searchService) {
        super(new BorderLayout());
        this.symbol = Objects.requireNonNull(symbol, "symbol");
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
                this.symbol.referenceQuery(),
                this.resultLimit,
                new ReferenceSearchService.Listener() {
                    @Override
                    public void onCompleted(ReferenceLocationPage result) {
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
        this.targetLabel.setText("Usages of " + this.symbol.displayName());
        this.targetLabel.setIcon(symbolIcon(this.symbol));

        Box firstRow = Box.createHorizontalBox();
        firstRow.add(this.targetLabel);
        firstRow.add(Box.createHorizontalGlue());
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

    private void showResult(ReferenceLocationPage result) {
        requireEdt();
        this.activeSearch = null;
        this.cancelButton.setVisible(false);
        this.resultTruncated = result.truncated();

        populateResults(result.locations());
        int classCount = (int) result.locations().stream().map(ReferenceLocation::className).distinct().count();
        if (result.truncated()) {
            if (this.resultLimit < MAX_RESULT_LIMIT) {
                this.statusLabel.setText(
                        "Showing " + result.locations().size() + " usage sites in " + classCount
                                + " classes. More are available."
                );
                this.showMoreButton.setVisible(true);
                this.showMoreButton.setEnabled(true);
            } else {
                this.statusLabel.setText(
                        "Showing " + result.locations().size() + " usage sites in " + classCount
                                + " classes. Additional sites are omitted."
                );
                this.showMoreButton.setVisible(false);
            }
        } else {
            this.statusLabel.setText(result.locations().size() + " usage sites in " + classCount + " classes");
            this.showMoreButton.setVisible(false);
        }

        if (result.locations().isEmpty()) {
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

    private void populateResults(List<ReferenceLocation> locations) {
        this.rootNode.removeAllChildren();
        Map<String, DefaultMutableTreeNode> classNodes = new LinkedHashMap<>();
        for (ReferenceLocation location : locations) {
            DefaultMutableTreeNode classNode = classNodes.computeIfAbsent(location.className(), className -> {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(new ClassNode(className));
                this.rootNode.add(node);
                return node;
            });
            classNode.add(new DefaultMutableTreeNode(new UsageNode(location)));
        }
        this.treeModel.reload();
        for (int row = 0; row < this.resultsTree.getRowCount(); row++) {
            this.resultsTree.expandRow(row);
        }
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
        if (CompanionApp.SERVER == null || !CompanionApp.SERVER.isClientConnected()) {
            this.statusLabel.setText("Not connected to the game client");
            return;
        }
        CompanionApp.send(
                ReferenceNavigationTarget.from(usage.location()).toMessage()
        );
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

    private record ClassNode(String className) {
        @Override
        public String toString() {
            return this.className;
        }
    }

    private record UsageNode(ReferenceLocation location) {
        @Override
        public String toString() {
            return usageLabel(this.location.site());
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
            if (userValue instanceof ClassNode) {
                setIcon(Icons.JAVA_CLASS);
            } else if (userValue instanceof UsageNode usage) {
                setIcon(locationIcon(usage.location().site()));
                setToolTipText(usage.location().className() + '#' + usageLabel(usage.location().site()));
            }
            return component;
        }
    }
}
