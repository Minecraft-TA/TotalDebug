package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.CapturePacketMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.PacketContentMessage;
import com.github.minecraft_ta.totalDebugCompanion.model.PacketView;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.util.TextUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import javax.swing.*;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import java.awt.*;

public class PacketViewPanel extends JPanel {

    private boolean isCapturing;

    public PacketViewPanel(PacketView packetView) {
        setLayout(new BorderLayout(0, 0));

        //Sends a packet to the game to get the packet content
        CompanionApp.send(new CapturePacketMessage(packetView.getPacket(), false));

        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        JTree tree = new JTree(root);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode treeNode && treeNode.getUserObject() instanceof TreeItem treeItem) {
                    setIcon(treeItem.getType().getIcon());
                }
                return this;
            }
        });
        BasicTreeUI treeUI = (BasicTreeUI) tree.getUI();
        treeUI.setCollapsedIcon(Icons.RIGHT_ARROW);
        treeUI.setExpandedIcon(Icons.DOWN_ARROW);

        //Adds a toggleable run button to the top of the panel
        FlatIconButton runButton = new FlatIconButton(Icons.RUN, true) {
            @Override
            public void setToggled(boolean b) {
                super.setToggled(b);
                this.setIcon(b ? Icons.PAUSE : Icons.RUN);
            }
        };

        //Adds a clear button to send a message to the game to clear the packet map
        FlatIconButton clearButton = new FlatIconButton(Icons.CLEAR, false);

        //Adds a ComboBox to select the amount of packets to show
        JComboBox<Integer> packetCountSelector = new JComboBox<>(new Integer[]{1, 5, 10, 20, 50, 100, 200, 500, 1000});
        packetCountSelector.setMaximumSize(new Dimension(200, (int) packetCountSelector.getPreferredSize().getHeight()));
        packetCountSelector.setSelectedIndex(5);

        //Add a header for the buttons and the direction selector
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.add(runButton);
        header.add(Box.createHorizontalStrut(5));
        header.add(clearButton);
        header.add(Box.createHorizontalStrut(5));
        header.add(packetCountSelector);
        add(header, BorderLayout.NORTH);

        //Pauses incoming packets when the run button is toggled
        runButton.addToggleListener(b -> {
            this.isCapturing = b;
        });

        //Clears the packet map when the clear button is pressed
        clearButton.addActionListener(e -> {
            root.removeAllChildren();
            ((DefaultTreeModel) tree.getModel()).nodeStructureChanged(root);
        });

        //Adds a packet and its content to the tree.
        CompanionApp.SERVER.getMessageBus().listenAlways(PacketContentMessage.class, this, message -> {
            //Return if logging is paused or if we already have more packets than the packet count selector allows
            //noinspection ConstantConditions
            if (!isCapturing || root.getChildCount() >= (int) packetCountSelector.getSelectedItem()) return;

            if (message.getPacketName().equals(packetView.getPacket())) {
                String packetName = TextUtils.htmlPrimarySecondaryString(message.getPacketName(), " ", "channel: " + message.getChannel() + ", size: " + message.getBytes() + "B");
                DefaultMutableTreeNode packetNode = jsonToTree(JsonParser.parseString(message.getPacketData()), packetName, Type.VALUE);
                DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
                model.insertNodeInto(packetNode, root, root.getChildCount());

                if (root.getChildCount() == 1) {
                    model.nodeStructureChanged((TreeNode) tree.getModel().getRoot());
                }
            }
        });

        add(new JScrollPane(tree));
    }

    /**
     * Converts a json string to a tree made of nodes using recursion.
     *
     * @param jsonElement The json element to convert.
     * @return The tree made of nodes.
     */
    private DefaultMutableTreeNode jsonToTree(JsonElement jsonElement, String name, Type type) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new TreeItem(name, type));
        if (jsonElement.isJsonObject()) {
            for (String key : jsonElement.getAsJsonObject().keySet()) {
                root.add(jsonToTree(jsonElement.getAsJsonObject().get(key), key, Type.FIELD));
            }
        } else if (jsonElement.isJsonArray()) {
            int i = 0;
            for (JsonElement element : jsonElement.getAsJsonArray()) {
                root.add(jsonToTree(element, String.valueOf(i), Type.VALUE));
                i++;
            }
            root.setUserObject(new TreeItem(TextUtils.htmlPrimarySecondaryString(name, " ", "size = " + jsonElement.getAsJsonArray().size()), Type.ARRAY));
        } else {
            root.setUserObject(new TreeItem(name + " = " + jsonElement.getAsString(), Type.PRIMITIVE));
        }
        return root;
    }

    public boolean canClose(String packetName) {
        CompanionApp.SERVER.getMessageBus().unregister(PacketContentMessage.class, this);
        CompanionApp.send(new CapturePacketMessage(packetName, true));
        return true;
    }

    private enum Type {
        FIELD(Icons.FIELD),
        PRIMITIVE(Icons.PRIMITIVE),
        VALUE(Icons.VALUE),
        ARRAY(Icons.ARRAY);

        private final Icon icon;

        Type(Icon icon) {
            this.icon = icon;
        }

        public Icon getIcon() {
            return icon;
        }
    }

    private static final class TreeItem {

        private final Type type;
        private final String value;

        public TreeItem(String value, Type icon) {
            this.value = value;
            this.type = icon;
        }

        public String getValue() {
            return value;
        }

        public Type getType() {
            return this.type;
        }

        @Override
        public String toString() {
            return value;
        }

    }
}
