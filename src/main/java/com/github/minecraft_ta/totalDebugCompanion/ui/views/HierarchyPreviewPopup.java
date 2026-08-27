package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyDirection;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyPage;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyQuery;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyResult;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.minecraft_ta.totalDebugCompanion.ui.HierarchyPresentation;
import com.github.minecraft_ta.totalDebugCompanion.ui.PopupChrome;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.PrimarySecondaryLabel;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.RuntimeModulePresentation;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.border.CompoundBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** A compact, non-interactive hierarchy preview shown while a gutter marker is hovered. */
public final class HierarchyPreviewPopup extends JWindow {
    private static final int RESULT_LIMIT = 6;

    private final CodeInsightService service;
    private final JLabel title = new JLabel();
    private final JPanel rows = new JPanel();
    private final Map<HierarchyQuery, HierarchyPage> cache = new HashMap<>();

    private CodeInsightService.SearchHandle activeSearch;
    private long generation;
    private Component invoker;
    private Rectangle sourceLine;

    public HierarchyPreviewPopup(Window owner, CodeInsightService service) {
        super(owner);
        this.service = Objects.requireNonNull(service, "service");
        setAlwaysOnTop(true);
        setFocusableWindowState(false);
        configureUi();
    }

    public void showHierarchy(
            Component invoker,
            Rectangle sourceLine,
            CodeSymbol symbol,
            HierarchyRelation relation,
            int count,
            boolean mixedBaseRelations
    ) {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(relation, "relation");
        if (count < 1) {
            throw new IllegalArgumentException("Hierarchy target count must be positive");
        }
        HierarchyQuery query;
        if (relation.direction() == HierarchyDirection.BASE_METHODS) {
            if (!(symbol instanceof CodeSymbol.MethodSymbol method)) {
                throw new IllegalArgumentException("Only methods have base declarations");
            }
            query = HierarchyQuery.baseMethods(method);
        } else {
            query = HierarchyQuery.implementations(symbol);
        }
        showPreview(invoker, sourceLine, query, relation, count, mixedBaseRelations);
    }

    public void setContentFont(Font font) {
        Font contentFont = Objects.requireNonNull(font, "font");
        this.rows.setFont(contentFont);
        this.title.setFont(contentFont.deriveFont(Font.BOLD));
    }

    public void hidePreview() {
        this.generation++;
        if (this.activeSearch != null) {
            this.activeSearch.cancel();
            this.activeSearch = null;
        }
        setVisible(false);
    }

    @Override
    public void dispose() {
        hidePreview();
        super.dispose();
    }

    private void configureUi() {
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(new CompoundBorder(PopupChrome.border(), PopupChrome.contentPadding()));
        setContentPane(content);

        this.title.setBorder(BorderFactory.createEmptyBorder(0, 2, 7, 2));
        content.add(this.title, BorderLayout.NORTH);

        this.rows.setLayout(new BoxLayout(this.rows, BoxLayout.Y_AXIS));
        content.add(this.rows, BorderLayout.CENTER);

    }

    private void showPreview(
            Component nextInvoker,
            Rectangle sourceLine,
            HierarchyQuery query,
            HierarchyRelation relation,
            int count,
            boolean mixedBaseRelations
    ) {
        this.invoker = Objects.requireNonNull(nextInvoker, "invoker");
        this.sourceLine = new Rectangle(Objects.requireNonNull(sourceLine, "sourceLine"));
        this.title.setText(HierarchyPresentation.previewTitle(relation, count, mixedBaseRelations));
        long searchGeneration = ++this.generation;
        if (this.activeSearch != null) {
            this.activeSearch.cancel();
            this.activeSearch = null;
        }

        if (query.direction() == HierarchyDirection.IMPLEMENTATIONS && count > 1) {
            this.rows.removeAll();
            showAtAnchor();
            return;
        }

        HierarchyPage cached = this.cache.get(query);
        if (cached != null) {
            showResults(cached, count);
            showAtAnchor();
            return;
        }

        setMessage("Looking up the hierarchy...");
        showAtAnchor();
        try {
            this.activeSearch = this.service.search(query, RESULT_LIMIT, new CodeInsightService.Listener<>() {
                @Override
                public void onCompleted(HierarchyPage page) {
                    if (searchGeneration != generation) {
                        return;
                    }
                    activeSearch = null;
                    cache.put(query, page);
                    showResults(page, count);
                    showAtAnchor();
                }

                @Override
                public void onFailed(Throwable failure) {
                    showFailure(searchGeneration, failure);
                }
            });
        } catch (RuntimeException failure) {
            showFailure(searchGeneration, failure);
        }
    }

    private void showFailure(long searchGeneration, Throwable failure) {
        if (searchGeneration != this.generation) {
            return;
        }
        this.activeSearch = null;
        setMessage("Hierarchy lookup failed");
        showAtAnchor();
        failure.printStackTrace(System.err);
    }

    private void showResults(HierarchyPage page, int totalCount) {
        this.rows.removeAll();
        if (page.results().isEmpty()) {
            addMessageRow("No declarations found");
        } else {
            for (HierarchyResult result : page.results()) {
                this.rows.add(createResultRow(result));
            }
        }
        if (page.truncated()) {
            int remaining = Math.max(0, totalCount - page.results().size());
            addMessageRow(remaining > 0 ? "+" + remaining + " more" : "Additional declarations");
        }
    }

    private JPanel createResultRow(HierarchyResult result) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));

        PrimarySecondaryLabel declaration = new PrimarySecondaryLabel();
        declaration.configure(
                ImplementationChooserPopup.resultPresentation(result.symbol()),
                ImplementationChooserPopup.symbolIcon(result.symbol()),
                this.rows.getFont(),
                false,
                null
        );
        row.add(declaration, BorderLayout.CENTER);

        RuntimeModulePresentation module = RuntimeModulePresentation.of(
                this.service.sourceCatalog().sourceFor(result.sourceId())
        );
        PrimarySecondaryLabel moduleLabel = new PrimarySecondaryLabel();
        moduleLabel.configure(
                module.text(),
                null,
                this.rows.getFont().deriveFont(
                        Font.PLAIN,
                        Math.max(10f, this.rows.getFont().getSize2D() - 1f)
                ),
                false,
                null
        );
        moduleLabel.setToolTipText(module.tooltip());
        row.add(moduleLabel, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        return row;
    }

    private void setMessage(String message) {
        this.rows.removeAll();
        addMessageRow(message);
    }

    private void addMessageRow(String message) {
        JLabel label = new JLabel(message);
        label.setForeground(ThemeColors.mutedText());
        label.setFont(this.rows.getFont());
        label.setBorder(BorderFactory.createEmptyBorder(4, 4, 5, 4));
        this.rows.add(label);
    }

    private void showAtAnchor() {
        this.rows.revalidate();
        this.rows.repaint();
        pack();
        int width = Math.max(360, Math.min(760, getWidth()));
        setSize(width, getHeight());

        PopupChrome.placeAdjacent(this, this.invoker, this.sourceLine);
        setVisible(true);
    }
}
