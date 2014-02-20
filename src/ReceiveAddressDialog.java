/**
 * Copyright 2013 Ronald W Hoffman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package BitcoinWallet;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;

/**
 * ReceiveAddressDialog displays a table containing labels and associated receive addresses.
 * The user can create a new entry, edit an entry or delete an entry.
 *
 * A receive address represents a public/private key pair owned by this wallet.  It is used
 * to receive coins.
 */
public class ReceiveAddressDialog extends JDialog implements ActionListener {

    /** Address table column classes */
    private static final Class<?>[] columnClasses = {
        String.class, String.class};

    /** Address table column names */
    private static final String[] columnNames = {
        "Name", "Address"};

    /** Transaction table column types */
    private static final int[] columnTypes = {
        SizedTable.NAME, SizedTable.ADDRESS};

    /** Address table model */
    private AddressTableModel tableModel;

    /** Address table */
    private JTable table;

    /** Address table scroll pane */
    private JScrollPane scrollPane;

    /**
     * Create the dialog
     *
     * @param       parent          Parent frame
     */
    public ReceiveAddressDialog(JFrame parent) {
        super(parent, "Receive Addresses", Dialog.ModalityType.DOCUMENT_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        //
        // Create the address table
        //
        tableModel = new AddressTableModel(columnNames, columnClasses);
        table = new SizedTable(tableModel, columnTypes);
        table.setRowSorter(new TableRowSorter<TableModel>(tableModel));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        //
        // Create the table scroll pane
        //
        scrollPane = new JScrollPane(table);
        //
        // Create the table pane
        //
        JPanel tablePane = new JPanel();
        tablePane.setBackground(Color.WHITE);
        tablePane.add(Box.createGlue());
        tablePane.add(scrollPane);
        tablePane.add(Box.createGlue());
        //
        // Create the buttons (New, Copy, Edit, Done)
        //
        JPanel buttonPane = new JPanel();
        buttonPane.setBackground(Color.white);

        buttonPane.add(Box.createVerticalStrut(15));

        JButton button = new JButton("New");
        button.setActionCommand("new");
        button.addActionListener(this);
        buttonPane.add(button);

        buttonPane.add(Box.createHorizontalStrut(10));

        button = new JButton("Copy");
        button.setActionCommand("copy");
        button.addActionListener(this);
        buttonPane.add(button);

        buttonPane.add(Box.createHorizontalStrut(10));

        button = new JButton("Edit");
        button.setActionCommand("edit");
        button.addActionListener(this);
        buttonPane.add(button);

        buttonPane.add(Box.createHorizontalStrut(10));

        button = new JButton("Done");
        button.setActionCommand("done");
        button.addActionListener(this);
        buttonPane.add(button);
        //
        // Set up the content pane
        //
        JPanel contentPane = new JPanel();
        contentPane.setLayout(new BoxLayout(contentPane, BoxLayout.Y_AXIS));
        contentPane.setOpaque(true);
        contentPane.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        contentPane.setBackground(Color.WHITE);
        contentPane.add(tablePane);
        contentPane.add(buttonPane);
        setContentPane(contentPane);
    }

    /**
     * Show the address list dialog
     *
     * @param       parent              Parent frame
     */
    public static void showDialog(JFrame parent) {
        try {
            JDialog dialog = new ReceiveAddressDialog(parent);
            dialog.pack();
            dialog.setLocationRelativeTo(parent);
            dialog.setVisible(true);
        } catch (Exception exc) {
            Main.logException("Exception while displaying dialog", exc);
        }
    }

    /**
     * Action performed (ActionListener interface)
     *
     * @param   ae              Action event
     */
    public void actionPerformed(ActionEvent ae) {

        //
        // "new"   - Create a new address entry
        // "copy"   - Copy an address to the system clipbboard
        // "edit"   - Edit an address entry
        // "done"   - All done
        //
        try {
            String action = ae.getActionCommand();
            if (action.equals("done")) {
                setVisible(false);
                dispose();
            } else if (action.equals("new")) {
                ECKey key = new ECKey();
                editKey(key, -1);
            } else {
                int row = table.getSelectedRow();
                if (row < 0) {
                    JOptionPane.showMessageDialog(this, "No entry selected", "Error", JOptionPane.ERROR_MESSAGE);
                } else {
                    row = table.convertRowIndexToModel(row);
                    switch (action) {
                        case "copy":
                            String address = (String)tableModel.getValueAt(row, 1);
                            StringSelection sel = new StringSelection(address);
                            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                            cb.setContents(sel, null);
                            break;
                        case "edit":
                            ECKey key = Parameters.keys.get(row);
                            editKey(key, row);
                            break;
                    }
                }
            }
        } catch (WalletException exc) {
            Main.logException("Unable to update wallet database", exc);
        } catch (Exception exc) {
            Main.logException("Exception while processing action event", exc);
        }
    }

    /**
     * Edit the key
     *
     * @param       key                 Key
     * @param       row                 Table row or -1 if the key is not in the table
     * @throws      WalletException     Unable to update database
     */
    private void editKey(ECKey key, int row) throws WalletException {
        //
        // Show the address edit dialog and validate the return label
        //
        Address addr = key.toAddress();
        while (true) {
            addr = AddressEditDialog.showDialog(this, addr, false);
            if (addr == null)
                break;
            String label = addr.getLabel();
            boolean valid = true;
            synchronized(Parameters.lock) {
                //
                // First pass checks for a duplicate label
                //
                for (int i=0; i<Parameters.keys.size(); i++) {
                    ECKey chkKey = Parameters.keys.get(i);
                    if (chkKey == key)
                        continue;
                    if (chkKey.getLabel().compareToIgnoreCase(label) == 0) {
                        JOptionPane.showMessageDialog(this, "Duplicate name specified", "Error",
                                                      JOptionPane.ERROR_MESSAGE);
                        valid = false;
                        break;
                    }
                }
                //
                // Second pass inserts the updated key in the list sorted by label
                //
                if (valid) {
                    if (row >= 0)
                        Parameters.keys.remove(row);
                    boolean added = false;
                    for (int i=0; i<Parameters.keys.size(); i++) {
                        ECKey chkKey = Parameters.keys.get(i);
                        if (chkKey.getLabel().compareToIgnoreCase(label) > 0) {
                            key.setLabel(label);
                            Parameters.keys.add(i, key);
                            added = true;
                            break;
                        }
                    }
                    if (!added) {
                        key.setLabel(label);
                        Parameters.keys.add(key);
                    }
                }
            }
            if (valid) {
                //
                // Update the database and load a new bloom filter if we generated a new key
                //
                if (row >= 0) {
                    Parameters.wallet.setKeyLabel(key);
                } else {
                    Parameters.wallet.storeKey(key);
                    Parameters.bloomFilter.insert(key.getPubKey());
                    Parameters.bloomFilter.insert(key.getPubKeyHash());
                    Message filterMsg = FilterLoadMessage.buildFilterLoadMessage(null, Parameters.bloomFilter);
                    Parameters.networkHandler.broadcastMessage(filterMsg);
                }
                //
                // Update the table
                //
                tableModel.fireTableDataChanged();
                break;
            }
        }
    }

    /**
     * AddressTableModel is the table model for the address dialog
     */
    private class AddressTableModel extends AbstractTableModel {

        /** Column names */
        private String[] columnNames;

        /** Column classes */
        private Class<?>[] columnClasses;

        /**
         * Create the table model
         *
         * @param       columnNames     Column names
         * @param       columnClasses   Column classes
         */
        public AddressTableModel(String[] columnNames, Class<?>[] columnClasses) {
            super();
            if (columnNames.length != columnClasses.length)
                throw new IllegalArgumentException("Number of names not same as number of classes");
            this.columnNames = columnNames;
            this.columnClasses = columnClasses;
        }

        /**
         * Get the number of columns in the table
         *
         * @return                  The number of columns
         */
        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        /**
         * Get the column class
         *
         * @param       column      Column number
         * @return                  The column class
         */
        @Override
        public Class<?> getColumnClass(int column) {
            return columnClasses[column];
        }

        /**
         * Get the column name
         *
         * @param       column      Column number
         * @return                  Column name
         */
        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        /**
         * Get the number of rows in the table
         *
         * @return                  The number of rows
         */
        @Override
        public int getRowCount() {
            return Parameters.keys.size();
        }

        /**
         * Get the value for a cell
         *
         * @param       row         Row number
         * @param       column      Column number
         * @return                  Returns the object associated with the cell
         */
        public Object getValueAt(int row, int column) {
            if (row >= Parameters.keys.size())
                throw new IndexOutOfBoundsException("Table row "+row+" is not valid");
            Object value;
            ECKey key = Parameters.keys.get(row);
            switch (column) {
                case 0:
                    value = key.getLabel();
                    break;
                case 1:
                    value = key.toAddress().toString();
                    break;
                default:
                    throw new IndexOutOfBoundsException("Table column "+column+" is not valid");
            }
            return value;
        }
    }
}
