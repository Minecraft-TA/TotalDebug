package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.messages.codeView.DecompileOrOpenMessage;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.AbstractCodeViewPanel;
import com.github.minecraft_ta.totalDebugCompanion.util.TextUtils;
import com.github.minecraft_ta.totalDebugCompanion.util.UIUtils;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.IndexedMethod;
import org.eclipse.jdt.core.IJavaElement;

import javax.swing.*;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FindImplementationsPopup extends BaseListPopup<FindImplementationsPopup.InternalListItem> {

    private final JList<InternalListItem> implementationsList = new JList<>(new DefaultListModel<>());
    {
        implementationsList.setLayout(new FlowLayout(FlowLayout.CENTER));
        implementationsList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                var component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setIcon(((InternalListItem) value).getIcon());
                return component;
            }
        });
    }
    private Component oldCenterComponent;
    private Component currentHeaderComponent;

    public FindImplementationsPopup(Window owner) {
        super(owner);
        setShowWhenEmpty(true);
        setList(this.implementationsList);
        setMinimumListWidth(400);
        addKeyEnterListener(InternalListItem::onAction);

        ((JPanel) getContentPane()).setBorder(DynamicMatteBorder.rule(1, 1, 1, 1));
    }

    @Override
    public void setItems(List<? extends InternalListItem> internalListItems) {
        updateEmptyState(internalListItems.isEmpty());
        super.setItems(internalListItems);
    }

    public void setItems(IndexedClass indexedClass) {
        updateHeader(indexedClass);
        setItems(Arrays.stream(indexedClass.findImplementations(false)).map(ClassListItem::new).toList());
    }

    public void setItems(IndexedMethod indexedMethod) {
        updateHeader(indexedMethod);
        setItems(Arrays.stream(indexedMethod.findImplementations()).map(MethodListItem::new).toList());
    }

    private void updateEmptyState(boolean empty) {
        if (this.oldCenterComponent != null && empty)
            return;
        if (this.oldCenterComponent != null) {
            remove(((BorderLayout) getRootPane().getContentPane().getLayout()).getLayoutComponent(BorderLayout.CENTER));
            add(this.oldCenterComponent, BorderLayout.CENTER);
            this.oldCenterComponent = null;
        } else if (empty) {
            var label = new JLabel("<html><span style=\"color:#E05555\">No implementations found</span></html>");
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setMinimumSize(new Dimension(50, 5));
            label.setFont(AbstractCodeViewPanel.JETBRAINS_MONO_FONT.deriveFont(13f));
            label.setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));
            this.oldCenterComponent = ((BorderLayout) getRootPane().getContentPane().getLayout()).getLayoutComponent(BorderLayout.CENTER);
            remove(this.oldCenterComponent);
            add(label, BorderLayout.CENTER);
        }
    }

    private void updateHeader(Object reference) {
        var header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        Component optionComponent;
        var headerTextParts = new ArrayList<JComponent>(4);
        switch (reference) {
            case IndexedClass indexedClass -> {
                var checkBox = new JCheckBox("Direct sub types only");
                checkBox.addItemListener(e -> {
                    setItems(Arrays.stream(indexedClass.findImplementations(e.getStateChange() == ItemEvent.SELECTED)).map(ClassListItem::new).toList());
                });
                optionComponent = checkBox;

                headerTextParts.add(new JLabel("Implementations of ", Icons.JAVA_CLASS, JLabel.LEFT));
                headerTextParts.add(new JLabel(colorTitleText(indexedClass.getName())));
            }
            case IndexedMethod indexedMethod -> {
                var baseMethods = new ArrayList<>(List.of(indexedMethod.findBaseMethods()));
                baseMethods.add(0, indexedMethod);
                var comboBox = new JComboBox<>(baseMethods.toArray());
                comboBox.addItemListener(e -> {
                    var item = (IndexedMethod) e.getItem();
                    setItems(Arrays.stream(item.findImplementations()).map(MethodListItem::new).toList());
                });
                comboBox.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                        var rendererComponent = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                        if (value instanceof IndexedMethod renderedMethod) {
                            setIcon(Icons.JAVA_METHOD);
                            setText(renderedMethod.getDeclaringClass().getNameWithPackageDot() + "." + renderedMethod.getName());
                        }
                        return rendererComponent;
                    }
                });
                optionComponent = UIUtils.horizontalLayout(new JLabel("Base method: "), comboBox);

                headerTextParts.add(new JLabel("Implementations of ", Icons.JAVA_METHOD, JLabel.LEFT));
                headerTextParts.add(new JLabel(colorTitleText(indexedMethod.getDeclaringClass().getName() + "." + indexedMethod.getName())));
            }
            default -> throw new IllegalArgumentException();
        }
        var textBox = new JPanel(new GridBagLayout());
        textBox.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        textBox.add(UIUtils.horizontalLayout(headerTextParts.toArray(new JComponent[0])));

        header.add(textBox, BorderLayout.NORTH);
        header.add(BorderLayout.WEST, optionComponent);

        if (this.currentHeaderComponent != null)
            remove(this.currentHeaderComponent);
        add(currentHeaderComponent = UIUtils.horizontalLayout(header), BorderLayout.NORTH);
    }

    private String colorTitleText(String indexedClass) {
        return "<html><span style='color: #CCC;'>%s</span></html>".formatted(indexedClass);
    }

    protected abstract static class InternalListItem implements ListItem {

        protected boolean checkConnection() {
            //TODO: Display in bottom information bar?
            return CompanionApp.SERVER.isClientConnected();
        }

        public abstract Icon getIcon();

        public abstract void onAction();

        public abstract String toString();
    }

    private static class ClassListItem extends InternalListItem {

        private final IndexedClass indexedClass;

        private ClassListItem(IndexedClass indexedClass) {
            this.indexedClass = indexedClass;
        }

        @Override
        public void onAction() {
            if (!checkConnection())
                return;

            CompanionApp.send(new DecompileOrOpenMessage(indexedClass.getNameWithPackageDot()));
        }

        @Override
        public Icon getIcon() {
            return Icons.JAVA_CLASS;
        }

        @Override
        public int getLabelLength() {
            return this.indexedClass.getNameWithPackageDot().length() + /* '/' */ 1 + /* ' ' */ 1;
        }

        @Override
        public String toString() {
            return TextUtils.htmlPrimarySecondaryString(indexedClass.getName(), " ", indexedClass.getPackage().getNameWithParentsDot());
        }
    }

    private static class MethodListItem extends InternalListItem {

        private final IndexedMethod indexedMethod;

        private MethodListItem(IndexedMethod indexedMethod) {
            this.indexedMethod = indexedMethod;
        }

        @Override
        public void onAction() {
            if (!checkConnection())
                return;

            CompanionApp.send(new DecompileOrOpenMessage(
                    indexedMethod.getDeclaringClass().getNameWithPackageDot(),
                    IJavaElement.METHOD,
                    indexedMethod.getName() + indexedMethod.getDescriptorString())
            );
        }

        @Override
        public Icon getIcon() {
            return Icons.JAVA_METHOD;
        }

        @Override
        public int getLabelLength() {
            var indexedClass = this.indexedMethod.getDeclaringClass();
            return indexedClass.getNameWithPackageDot().length() + /* '/' */ 1 + /* ' ' */ 1;
        }

        @Override
        public String toString() {
            var indexedClass = this.indexedMethod.getDeclaringClass();
            return TextUtils.htmlPrimarySecondaryString(indexedClass.getName(), " ", indexedClass.getPackage().getNameWithParentsDot());
        }
    }
}
