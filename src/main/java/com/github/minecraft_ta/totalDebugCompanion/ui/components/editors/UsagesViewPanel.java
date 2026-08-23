package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceSearchPhase;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceSearchProgress;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceSearchResult;
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
import javax.swing.JProgressBar;
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

    private final CodeSymbol symbol;
    private final ReferenceSearchService searchService;
    private final JLabel targetLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JProgressBar progressBar = new JProgressBar();
    private final JButton cancelButton = new JButton("Cancel");
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(this.rootNode);
    private final JTree resultsTree = new JTree(this.treeModel);
    private final JLabel messageLabel = new JLabel("Searching runtime bytecode…", SwingConstants.CENTER);
    private final JPanel resultCards = new JPanel(new CardLayout());

    private ReferenceSearchService.SearchHandle activeSearch;
    private long searchGeneration;
    private boolean detached;

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
        cancelActiveSearch();
        long generation = ++this.searchGeneration;

        this.rootNode.removeAllChildren();
        this.treeModel.reload();
        this.messageLabel.setText("Searching runtime bytecode…");
        showCard(MESSAGE_CARD);
        this.statusLabel.setText("Preparing runtime sources");
        this.progressBar.setMinimum(0);
        this.progressBar.setMaximum(1);
        this.progressBar.setValue(0);
        this.progressBar.setIndeterminate(true);
        this.progressBar.setStringPainted(false);
        this.cancelButton.setEnabled(true);

        this.activeSearch = this.searchService.search(this.symbol.referenceQuery(), new ReferenceSearchService.Listener() {
            @Override
            public void onProgress(ReferenceSearchProgress progress) {
                if (isCurrent(generation)) {
                    updateProgress(progress);
                }
            }

            @Override
            public void onCompleted(ReferenceSearchResult result) {
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
        });
    }

    private void configureHeader() {
        this.targetLabel.setText("Usages of " + this.symbol.displayName());
        this.targetLabel.setIcon(symbolIcon(this.symbol));

        Box firstRow = Box.createHorizontalBox();
        firstRow.add(this.targetLabel);
        firstRow.add(Box.createHorizontalGlue());
        firstRow.add(this.cancelButton);

        this.progressBar.setStringPainted(false);
        Box secondRow = Box.createHorizontalBox();
        secondRow.add(this.statusLabel);
        secondRow.add(Box.createHorizontalStrut(12));
        secondRow.add(this.progressBar);

        JPanel header = new JPanel();
        header.setLayout(new javax.swing.BoxLayout(header, javax.swing.BoxLayout.Y_AXIS));
        header.add(firstRow);
        header.add(Box.createVerticalStrut(6));
        header.add(secondRow);
        header.setBorder(new CompoundBorder(
                DynamicMatteBorder.separatorRule(0, 0, 1, 0),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        add(header, BorderLayout.NORTH);

        this.cancelButton.addActionListener(event -> cancelActiveSearch());
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

    private void updateProgress(ReferenceSearchProgress progress) {
        requireEdt();
        this.progressBar.setIndeterminate(false);
        this.progressBar.setMaximum(Math.max(1, progress.totalClassFiles()));
        this.progressBar.setValue(progress.processedClassFiles());
        this.progressBar.setStringPainted(true);
        this.progressBar.setString(progress.processedClassFiles() + " / " + progress.totalClassFiles());
        this.statusLabel.setText(progress.phase() == ReferenceSearchPhase.RESOLVING_MEMBER_OWNERS
                ? "Resolving declaring types"
                : "Scanning references");
    }

    private void showResult(ReferenceSearchResult result) {
        requireEdt();
        this.activeSearch = null;
        this.cancelButton.setEnabled(false);
        this.progressBar.setIndeterminate(false);
        this.progressBar.setMaximum(Math.max(1, result.totalClassFiles()));
        this.progressBar.setValue(result.scannedClassFiles());
        this.progressBar.setStringPainted(true);
        this.progressBar.setString(result.scannedClassFiles() + " / " + result.totalClassFiles());

        populateResults(result.locations());
        int classCount = (int) result.locations().stream().map(ReferenceLocation::className).distinct().count();
        if (result.cancelled()) {
            this.statusLabel.setText("Cancelled — " + result.locations().size() + " partial usage sites");
        } else {
            this.statusLabel.setText(result.locations().size() + " usage sites in " + classCount + " classes");
        }

        if (result.locations().isEmpty()) {
            this.messageLabel.setText(result.cancelled() ? "Search cancelled" : "No usages found");
            showCard(MESSAGE_CARD);
        } else {
            showCard(RESULTS_CARD);
        }
    }

    private void showFailure(Throwable failure) {
        requireEdt();
        this.activeSearch = null;
        this.cancelButton.setEnabled(false);
        this.progressBar.setIndeterminate(false);
        this.progressBar.setStringPainted(false);
        String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        this.statusLabel.setText("Reference search failed");
        this.messageLabel.setText("Unable to search runtime bytecode: " + detail);
        showCard(MESSAGE_CARD);
        failure.printStackTrace(System.err);
    }

    private void populateResults(List<ReferenceLocation> locations) {
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
        CompanionApp.SERVER.getMessageProcessor().enqueueMessage(
                ReferenceNavigationTarget.from(usage.location()).toMessage()
        );
    }

    private void cancelActiveSearch() {
        if (this.activeSearch != null && !this.activeSearch.isDone()) {
            this.activeSearch.cancel();
            this.statusLabel.setText("Cancelling…");
            this.cancelButton.setEnabled(false);
        }
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
