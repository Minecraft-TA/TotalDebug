package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.formdev.flatlaf.extras.components.FlatComboBox;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.messages.packetLogger.*;
import com.github.minecraft_ta.totalDebugCompanion.model.PacketView;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearch;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.MainWindow;
import com.github.minecraft_ta.totalDebugCompanion.util.Pair;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

public class PacketLoggerViewPanel extends JPanel {

    private static final SimpleDateFormat SIMPLE_DATE_FORMAT = new SimpleDateFormat("mm:ss:SSS");
    private long startTime = -1;

    /**
     * Creates a JTable with three columns: Packet Name, amount of times it was received and total size of the packets in bytes
     * Updates the table every time a new packet is received using the companion app's message bus
     */
    public PacketLoggerViewPanel() {
        setLayout(new BorderLayout(0, 0));

        //Adds a toggleable run button to the top of the panel
        FlatIconButton runButton = new FlatIconButton(Icons.RUN, true) {
            @Override
            public void setToggled(boolean b) {
                super.setToggled(b);
                this.setIcon(b ? Icons.PAUSE : Icons.RUN);
            }
        };

        //Add a clear button to send a message to the game to clear the packet map
        FlatIconButton clearButton = new FlatIconButton(Icons.CLEAR, false);

        //Add a combo box to select the packets to view
        FlatComboBox<String> packetSelector = new FlatComboBox<>();
        packetSelector.addItem("Incoming Packets");
        packetSelector.addItem("Outgoing Packets");
        packetSelector.setEditable(false);
        packetSelector.setMaximumSize(new Dimension(200, (int) packetSelector.getPreferredSize().getHeight()));

        //Adds a combo box to filter the packets by the channel they were sent on
        FlatComboBox<String> channelSelector = new FlatComboBox<>();
        channelSelector.addItem("All channels");
        channelSelector.setEditable(false);
        channelSelector.setMaximumSize(new Dimension(200, (int) channelSelector.getPreferredSize().getHeight()));

        //Sends a message to the game to request the channel list
        CompanionApp.send(new ChannelListMessage());

        //Adds a timer at the rop right corner of the panel to display how long the packet logger has been running
        JLabel timeLabel = new JLabel("00:00:00");
        timeLabel.setIcon(Icons.CLOCK);
        timeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        Timer timer = new Timer(50, e -> {
            if (startTime == -1) {
                startTime = System.currentTimeMillis();
            }
            long time = System.currentTimeMillis() - startTime;
            timeLabel.setText(SIMPLE_DATE_FORMAT.format(time));
        });

        //Adds a placeholder to the panel
        JLabel placeholder = new JLabel("Press the run button to start logging packets");
        placeholder.setOpaque(false);

        //Add a header for the buttons and the direction selector
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.add(runButton);
        header.add(Box.createHorizontalStrut(5));
        header.add(clearButton);
        header.add(Box.createHorizontalStrut(5));
        header.add(packetSelector);
        header.add(Box.createHorizontalStrut(5));
        header.add(channelSelector);
        header.add(Box.createHorizontalGlue());
        header.add(timeLabel);
        header.add(Box.createHorizontalStrut(5));

        add(header, BorderLayout.NORTH);

        //Creates the table
        JTable table = new JTable() {
            private final DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();

            {
                rightRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
            }

            @Override
            public TableCellRenderer getCellRenderer(int row, int column) {
                if (column == 2) {
                    return rightRenderer;
                }
                return super.getCellRenderer(row, column);
            }
        };

        table.setModel(new DefaultTableModel(new Object[][]{}, new String[]{"Packet Name", "Amount", "Bytes"}) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 0 -> String.class;
                    case 1 -> Integer.class;
                    default -> ByteWrapper.class;
                };
            }
        });
        //Adds a sorter to the table
        TableRowSorter<TableModel> sorter = new TableRowSorter<>(table.getModel());
        sorter.setSortsOnUpdates(true);
        table.setRowSorter(sorter);

        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setGridColor(getBackground());
        table.getColumnModel().getColumn(0).setPreferredWidth(280);
        SpeedSearch.install(table, row -> {
            StringBuilder text = new StringBuilder();
            for (int column = 0; column < table.getColumnCount(); column++) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(table.getValueAt(row, column));
            }
            return text.toString();
        });
        table.getInputMap(WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openPacket");
        table.getActionMap().put("openPacket", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                openSelectedPacket(table);
            }
        });
        table.setLayout(new GridBagLayout());
        table.add(placeholder);
        table.getModel().addTableModelListener(e -> {
            TableModel m = (TableModel) e.getSource();
            placeholder.setVisible(m.getRowCount() == 0);
        });
        add(new JScrollPane(table));

        CompanionApp.SERVER.getMessageBus().listenAlways(IncomingPacketsMessage.class, this, incomingPacketsMessage -> {
            SwingUtilities.invokeLater(() -> {
                if (packetSelector.getSelectedIndex() == 0) {
                    updateTable(table, incomingPacketsMessage.getIncomingPackets());
                }
            });
        });

        CompanionApp.SERVER.getMessageBus().listenAlways(OutgoingPacketsMessage.class, this, outgoingPacketsMessage -> {
            SwingUtilities.invokeLater(() -> {
                if (packetSelector.getSelectedIndex() == 1) {
                    updateTable(table, outgoingPacketsMessage.getOutgoingPackets());
                }
            });
        });

        CompanionApp.SERVER.getMessageBus().listenAlways(ChannelListMessage.class, this, channelListMessage -> {
            SwingUtilities.invokeLater(() -> channelListMessage.getChannels().forEach(channelSelector::addItem));
        });

        //Add a listener to the run button to send a message to the game to start or stop logging packets
        runButton.addToggleListener(b -> {
            if (b) {
                timer.start();
                try {
                    Date date = SIMPLE_DATE_FORMAT.parse(timeLabel.getText());
                    startTime = System.currentTimeMillis() - date.getTime();
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            } else {
                timer.stop();
            }
            int selectedIndex = packetSelector.getSelectedIndex();
            CompanionApp.send(new PacketLoggerStateChangeMessage(selectedIndex == 0 && b, selectedIndex == 1 && b));
        });

        //Add a listener to the clear button to send a message to the game to clear the packet map also clears the table
        clearButton.addActionListener(e -> {
            startTime = -1;
            timeLabel.setText("00:00:00");
            CompanionApp.send(new ClearPacketsMessage());
            ((DefaultTableModel) table.getModel()).setRowCount(0);
        });

        //Add a listener to the direction selector to send a message to the game to change the direction of the packets also clears the table
        packetSelector.addActionListener(e -> {
            if (runButton.isToggled()) {
                int selectedIndex = packetSelector.getSelectedIndex();
                CompanionApp.send(new PacketLoggerStateChangeMessage(selectedIndex == 0, selectedIndex == 1));
            }
            startTime = -1;
            timeLabel.setText("00:00:00");
            ((DefaultTableModel) table.getModel()).setRowCount(0);
        });

        //Add a listener to the channel selector to send a message to the game to change the channel of the packets also clears the table
        channelSelector.addActionListener(e -> {
            CompanionApp.send(new SetChannelMessage((String) channelSelector.getSelectedItem()));
            CompanionApp.send(new ClearPacketsMessage());
            ((DefaultTableModel) table.getModel()).setRowCount(0);
        });

        //Adds the ability to pause using the space bar
        table.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                    runButton.toggle();
                }
            }
        });

        //Adds a popup menu to the table that allows to decompile the selected packet
        JPopupMenu popup = new JPopupMenu();
        JMenuItem decompile = new JMenuItem("Decompile");
        decompile.setIcon(Icons.DECOMPILE);
        decompile.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row != -1) {
                String packet = (String) table.getValueAt(row, 0);
                CompanionApp.openClass(packet);
            }
        });
        popup.add(decompile);

        //Adds a popup menu to the table that allows the user to copy the selected packet to the clipboard
        JMenuItem copy = new JMenuItem("Copy");
        copy.setIcon(Icons.COPY);
        copy.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row != -1) {
                String packet = (String) table.getValueAt(row, 0);
                StringSelection selection = new StringSelection(packet);
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(selection, selection);
            }
        });
        popup.add(copy);

        //Adds a popup menu to block the selected packet
        JMenuItem block = new JMenuItem("Block Packet");
        block.setIcon(Icons.BLOCK);
        block.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row != -1) {
                String packet = (String) table.getValueAt(row, 0);
                CompanionApp.send(new BlockPacketMessage(packet));
            }
        });
        popup.add(block);


        //Adds a right click menu to the table
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (e.getButton() == MouseEvent.BUTTON3 && row != -1) {
                    table.setRowSelectionInterval(row, row);
                    popup.show(table, e.getX(), e.getY());
                } else if (e.getClickCount() == 2 && row != -1) {
                    openSelectedPacket(table);
                }
            }
        });
    }

    private static void openSelectedPacket(JTable table) {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        String packet = (String) table.getValueAt(row, 0);
        MainWindow.INSTANCE.getEditorTabs().focusOrCreateIfAbsent(
                PacketView.class,
                view -> view.getPacket().equals(packet),
                () -> new PacketView(packet)
        );
    }

    /**
     * Updates the table with the given packet map
     *
     * @param table   The table to update
     * @param packets The packet map to update the table with
     */
    private void updateTable(JTable table, Map<String, Pair<Integer, Integer>> packets) {
        //Updates the already existing rows with the new values
        for (int i = 0; i < table.getRowCount(); i++) {
            String value = (String) table.getValueAt(i, 0);
            Pair<Integer, Integer> packetData = packets.remove(value);
            if (packetData != null) {
                table.setValueAt(packetData.a(), i, 1);
                table.setValueAt(new ByteWrapper(packetData.b()), i, 2);
            }
        }

        //Adds any new packets to the table
        for (Map.Entry<String, Pair<Integer, Integer>> entry : packets.entrySet()) {
            ((DefaultTableModel) table.getModel()).addRow(new Object[]{entry.getKey(), entry.getValue().a(), new ByteWrapper(entry.getValue().b())});
        }
    }

    public boolean canClose() {
        CompanionApp.send(new PacketLoggerStateChangeMessage(false, false));
        CompanionApp.send(new ClearPacketsMessage());
        CompanionApp.send(new SetChannelMessage("All channels"));
        CompanionApp.SERVER.getMessageBus().unregister(IncomingPacketsMessage.class, this);
        CompanionApp.SERVER.getMessageBus().unregister(OutgoingPacketsMessage.class, this);
        CompanionApp.SERVER.getMessageBus().unregister(ChannelListMessage.class, this);
        return true;
    }

    record ByteWrapper(int bytes) implements Comparable<ByteWrapper> {

        @Override
        public int compareTo(ByteWrapper o) {
            return Integer.compare(bytes, o.bytes);
        }

        /**
         * Converts the amount of bytes to a human-readable format
         *
         * @return A string representing the amount of bytes
         */
        @Override
        public String toString() {
            if (bytes < 1024) {
                return bytes + " B";
            } else if (bytes < 1024 * 1024) {
                return formatBytes(bytes / 1024.0) + " KB";
            } else {
                return formatBytes(bytes / (1024.0 * 1024.0)) + " MB";
            }
        }

        private String formatBytes(double bytes) {
            return String.format("%.2f", bytes);
        }
    }
}
