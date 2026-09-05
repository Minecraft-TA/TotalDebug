package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;

/** A live evaluation result whose children belong to one debugger pause. */
public final class DebuggerResultPanel extends JPanel implements AutoCloseable {
    private DebuggerValueLease retained = DebuggerValueLease.NONE;
    private boolean closed;

    public DebuggerResultPanel(DebuggerSessionController controller, String pauseId, DebugEngine.EvaluationResult result) {
        super(new BorderLayout());
        var root = new DefaultMutableTreeNode();
        root.add(DebuggerValueTree.valueNode(DebuggerValueTree.DebugValue.from("Result", result)));
        var model = new DefaultTreeModel(root);
        var tree = DebuggerValueTree.create(model);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) {
                var node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                var value = DebuggerValueTree.debugValue(node.getUserObject());
                if (value == null || !DebuggerValueTree.hasPlaceholder(node)) return;
                node.removeAllChildren();
                node.add(new DefaultMutableTreeNode(new DebuggerValueTree.StatusValue("Loading...", false)));
                model.nodeStructureChanged(node);
                controller.variables(pauseId, null, value.variablesReference(), 0, 501)
                        .whenComplete((variables, failure) -> SwingUtilities.invokeLater(() -> {
                            node.removeAllChildren();
                            if (failure != null) {
                                node.add(new DefaultMutableTreeNode(new DebuggerValueTree.StatusValue(failure.getMessage(), true)));
                            } else {
                                variables.stream().limit(500).forEach(variable -> node.add(DebuggerValueTree.valueNode(
                                        DebuggerValueTree.DebugValue.from(variable))));
                                if (variables.size() > 500) node.add(new DefaultMutableTreeNode(
                                        new DebuggerValueTree.StatusValue("Showing the first 500 values", false)));
                            }
                            model.nodeStructureChanged(node);
                        }));
            }
            @Override public void treeWillCollapse(TreeExpansionEvent event) { }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
        controller.retainValue(pauseId, result.variablesReference()).whenComplete((value, failure) -> {
            synchronized (this) {
                if (failure == null) {
                    if (this.closed) value.close();
                    else this.retained = value;
                } else SwingUtilities.invokeLater(() -> {
                    synchronized (this) {
                        if (this.closed) return;
                        add(new JLabel("Evaluation result expired: " + failure.getMessage()), BorderLayout.SOUTH);
                        revalidate();
                    }
                });
            }
        });
    }

    @Override public synchronized void close() {
        this.closed = true;
        this.retained.close();
        this.retained = DebuggerValueLease.NONE;
    }
}
