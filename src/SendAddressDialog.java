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
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;

/**
 * SendAddressDialog displays a table containing names and associated addresses.  The user can create a new
 * entry, edit an entry or delete an entry.
 *
 * A send address represents an address used to send coins and does not have an associated
 * public/private key pair in the wallet.  It is valid for a user to send coins to himself
 * by creating a send address that is the same as a receive address.
 */
public class SendAddressDialog extends JDialog implements ActionListener {

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
    public SendAddressDialog(JFrame parent) {
        super(parent, "Send Addresses", Dialog.ModalityType.DOCUMENT_MODAL);
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
        // Create the buttons (New, Copy, Edit, Delete, Done)
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

        button = new JButton("Delete");
        button.setActionCommand("delete");
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
            JDialog dialog = new SendAddressDialog(parent);
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
    @Override
    public void actionPerformed(ActionEvent ae) {
        //
        // "new"   - Create a new address entry
        // "copy"   - Copy an address to the system clipbboard
        // "edit"   - Edit an address entry
        // "delete" - Delete an address entry
        // "done"   - All done
        //
        try {
            String action = ae.getActionCommand();
            if (action.equals("done")) {
                setVisible(false);
                dispose();
            } else if (action.equals("new")) {
                editAddress(null, -1);
            } else {
                int row = table.getSelectedRow();
                if (row < 0) {
                    JOptionPane.showMessageDialog(this, "No entry selected", "Error", JOptionPane.ERROR_MESSAGE);
                } else {
                    row = table.convertRowIndexToModel(row);
                    Address addr = Parameters.addresses.get(row);
                    switch (action) {
                        case "copy":
                            StringSelection sel = new StringSelection(addr.toString());
                            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                            cb.setContents(sel, null);
                            break;
                        case "edit":
                            editAddress(addr, row);
                            break;
                        case "delete":
                            Parameters.addresses.remove(row);
                            Parameters.wallet.deleteAddress(addr);
                            tableModel.fireTableDataChanged();
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
     * Edit the address
     *
     * @param       address             Address
     * @param       row                 Table row or -1 if the key is not in the table
     * @throws      WalletException     Unable to update database
     */
    private void editAddress(Address address, int row) throws WalletException {
        //
        // Show the address edit dialog and validate the return label
        //
        Address addr = address;
        while (true) {
            addr = AddressEditDialog.showDialog(this, addr, true);
            if (addr == null)
                break;
            String label = addr.getLabel();
            boolean valid = true;
            synchronized(Parameters.lock) {
                //
                // First pass checks for a duplicate label
                //
                for (int i=0; i<Parameters.addresses.size(); i++) {
                    Address chkAddr = Parameters.addresses.get(i);
                    if (chkAddr == address)
                        continue;
                    if (chkAddr.getLabel().compareToIgnoreCase(label) == 0) {
                        JOptionPane.showMessageDialog(this, "Duplicate name specified", "Error",
                                                      JOptionPane.ERROR_MESSAGE);
                        valid = false;
                        break;
                    }
                }
                //
                // Second pass inserts the updated address in the list sorted by label
                //
                if (valid) {
                    if (row >= 0)
                        Parameters.addresses.remove(row);
                    boolean added = false;
                    for (int i=0; i<Parameters.addresses.size(); i++) {
                        Address chkAddr = Parameters.addresses.get(i);
                        if (chkAddr.getLabel().compareToIgnoreCase(label) > 0) {
                            Parameters.addresses.add(i, addr);
                            added = true;
                            break;
                        }
                    }
                    if (!added) {
                        Parameters.addresses.add(addr);
                    }
                }
            }
            if (valid) {
                if (row >= 0)
                    Parameters.wallet.setAddressLabel(addr);
                else
                    Parameters.wallet.storeAddress(addr);
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
            return Parameters.addresses.size();
        }

        /**
         * Get the value for a cell
         *
         * @param       row         Row number
         * @param       column      Column number
         * @return                  Returns the object associated with the cell
         */
        @Override
        public Object getValueAt(int row, int column) {
            if (row >= Parameters.addresses.size())
                throw new IndexOutOfBoundsException("Table row "+row+" is not valid");
            Object value;
            Address addr = Parameters.addresses.get(row);
            switch (column) {
                case 0:
                    value = addr.getLabel();
                    break;
                case 1:
                    value = addr.toString();
                    break;
                default:
                    throw new IndexOutOfBoundsException("Table column "+column+" is not valid");
            }
            return value;
        }
    }
}
