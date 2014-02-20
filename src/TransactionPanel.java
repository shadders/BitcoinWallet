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

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;

/**
 * TransactionPanel displays a table containing all of the wallet transactions
 */
public class TransactionPanel extends JPanel implements ActionListener {

    /** Transaction table column classes */
    private static final Class<?>[] columnClasses = {
        Date.class, String.class, String.class, String.class, BigInteger.class, BigInteger.class,
        String.class, String.class};

    /** Transaction table column names */
    private static final String[] columnNames = {
        "Date", "Transaction ID", "Type", "Name/Address", "Amount", "Fee",
        "Location", "Status"};

    /** Transaction table column types */
    private static final int[] columnTypes = {
        SizedTable.DATE, SizedTable.ADDRESS, SizedTable.TYPE, SizedTable.ADDRESS, SizedTable.AMOUNT,
        SizedTable.AMOUNT, SizedTable.STATUS, SizedTable.STATUS};

    /** Wallet balance field */
    private JLabel walletLabel;

    /** Safe balance field */
    private JLabel safeLabel;

    /** Transaction table scroll pane */
    private JScrollPane scrollPane;

    /** Transaction table */
    private JTable table;

    /** Transaction table model */
    private TransactionTableModel tableModel;

    /** Safe balance */
    private BigInteger safeBalance;

    /** Wallet balance */
    private BigInteger walletBalance;

    /**
     * Create the transaction panel
     *
     * @param       parentFrame     Parent frame
     */
    public TransactionPanel(JFrame parentFrame) {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        //
        // Create the transaction table
        //
        tableModel = new TransactionTableModel(columnNames, columnClasses);
        table = new SizedTable(tableModel, columnTypes);
        table.setRowSorter(new TableRowSorter<TableModel>(tableModel));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        String frameSize = Main.properties.getProperty("window.main.size");
        if (frameSize != null) {
            int sep = frameSize.indexOf(',');
            int frameWidth = Integer.parseInt(frameSize.substring(0, sep));
            int frameHeight = Integer.parseInt(frameSize.substring(sep+1));
            table.setPreferredScrollableViewportSize(new Dimension(frameWidth-120, frameHeight-220));
        }
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
        // Create the status pane containing the Wallet balance and Safe balance
        //
        JPanel statusPane = new JPanel();
        statusPane.setOpaque(true);
        statusPane.setBackground(Color.WHITE);

        walletLabel = new JLabel(getWalletText());
        statusPane.add(walletLabel);

        statusPane.add(Box.createHorizontalStrut(50));

        safeLabel = new JLabel(getSafeText());
        statusPane.add(safeLabel);
        //
        // Create the buttons (Move to Safe, Move to Wallet, Delete Transaction)
        //
        JPanel buttonPane = new JPanel();
        buttonPane.setBackground(Color.white);

        JButton button = new JButton("Move to Safe");
        button.setActionCommand("move to safe");
        button.addActionListener(this);
        buttonPane.add(button);

        buttonPane.add(Box.createHorizontalStrut(15));

        button = new JButton("Move to Wallet");
        button.setActionCommand("move to wallet");
        button.addActionListener(this);
        buttonPane.add(button);

        buttonPane.add(Box.createHorizontalStrut(15));

        button = new JButton("Delete Transaction");
        button.setActionCommand("delete tx");
        button.addActionListener(this);
        buttonPane.add(button);
        //
        // Set up the content pane
        //
        add(statusPane, BorderLayout.NORTH);
        add(tablePane, BorderLayout.CENTER);
        add(buttonPane, BorderLayout.SOUTH);
    }

    /**
     * Action performed (ActionListener interface)
     *
     * @param       ae              Action event
     */
    @Override
    public void actionPerformed(ActionEvent ae) {

        //
        // "move to safe"   - Move transaction to the safe
        // "move to wallet" - Move transaction to the wallet
        // "delete tx"      - Delete transaction
        //
        try {
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(this, "No transaction selected", "Error", JOptionPane.ERROR_MESSAGE);
            } else {
                row = table.convertRowIndexToModel(row);
                String action = ae.getActionCommand();
                switch (action) {
                    case "move to safe":
                        if (moveToSafe(row)) {
                            tableModel.fireTableRowsUpdated(row, row);
                            walletLabel.setText(getWalletText());
                            safeLabel.setText(getSafeText());
                        }
                        break;
                    case "move to wallet":
                        if (moveToWallet(row)) {
                            tableModel.fireTableRowsUpdated(row, row);
                            walletLabel.setText(getWalletText());
                            safeLabel.setText(getSafeText());
                        }
                        break;
                    case "delete tx":
                        if (deleteTx(row)) {
                            walletLabel.setText(getWalletText());
                            safeLabel.setText(getSafeText());
                        }
                        break;
                }
            }
        } catch (WalletException exc) {
            Main.logException("Unable to update wallet", exc);
        } catch (Exception exc) {
            Main.logException("Exception while processing action event", exc);
        }
    }

    /**
     * The wallet has changed
     */
    public void walletChanged() {
        int row = table.getSelectedRow();
        tableModel.walletChanged();
        if (row >= 0)
            table.setRowSelectionInterval(row, row);
        walletLabel.setText(getWalletText());
        safeLabel.setText(getSafeText());
    }

    /**
     * Transaction status has changed
     */
    public void statusChanged() {
        tableModel.fireTableDataChanged();
    }

    /**
     * Move a transaction from the wallet to the safe
     *
     * We will not move a transaction unless it has spendable coins
     *
     * @param       row                 The transaction row
     * @return                          TRUE if the transaction was moved
     * @throws      WalletException     Unable to update wallet
     */
    private boolean moveToSafe(int row) throws WalletException {
        WalletTransaction tx = tableModel.getTransaction(row);
        if (!(tx instanceof ReceiveTransaction)) {
            JOptionPane.showMessageDialog(this, "The safe contains coins that you have received and not spent",
                                          "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        ReceiveTransaction rcvTx = (ReceiveTransaction)tx;
        if (rcvTx.inSafe()) {
            JOptionPane.showMessageDialog(this, "The transaction is already in the safe",
                                          "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (rcvTx.isSpent()) {
            JOptionPane.showMessageDialog(this, "The coins have already been spent",
                                          "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        Parameters.wallet.setTxSafe(rcvTx.getTxHash(), rcvTx.getTxIndex(), true);
        rcvTx.setSafe(true);
        safeBalance = safeBalance.add(rcvTx.getValue());
        walletBalance = walletBalance.subtract(rcvTx.getValue());
        return true;
    }

    /**
     * Move a transaction from the safe to the wallet
     *
     * @param       row                 The transaction row
     * @return                          TRUE if the transaction was moved
     * @throws      WalletException     Unable to update wallet
     */
    private boolean moveToWallet(int row) throws WalletException {
        WalletTransaction tx = tableModel.getTransaction(row);
        if (!(tx instanceof ReceiveTransaction)) {
            JOptionPane.showMessageDialog(this, "The safe contains coins that you have received and not spent",
                                          "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        ReceiveTransaction rcvTx = (ReceiveTransaction)tx;
        if (!rcvTx.inSafe()) {
            JOptionPane.showMessageDialog(this, "The transaction is not in the safe",
                                          "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        Parameters.wallet.setTxSafe(rcvTx.getTxHash(), rcvTx.getTxIndex(), false);
        walletBalance = walletBalance.add(rcvTx.getValue());
        safeBalance = safeBalance.subtract(rcvTx.getValue());
        rcvTx.setSafe(false);
        return true;
    }

    /**
     * Delete a transaction
     *
     * @param       row                 The transaction row
     * @return                          TRUE if the transaction was deleted
     * @throws      WalletException     Unable to update wallet
     */
    private boolean deleteTx(int row) throws WalletException {
        WalletTransaction tx = tableModel.getTransaction(row);
        if (tx instanceof ReceiveTransaction) {
            //
            // A receive transaction can be deleted as long as it hasn't been spent
            //
            ReceiveTransaction rcvTx = (ReceiveTransaction)tx;
            if (rcvTx.isSpent()) {
                JOptionPane.showMessageDialog(this, "The transaction has been spent",
                                              "Error", JOptionPane.ERROR_MESSAGE);
                return false;
            }
            Parameters.wallet.setReceiveTxDelete(rcvTx.getTxHash(), rcvTx.getTxIndex(), true);
            tableModel.deleteTransaction(row);
            if (rcvTx.inSafe())
                safeBalance = safeBalance.subtract(rcvTx.getValue());
            else
                walletBalance = walletBalance.subtract(rcvTx.getValue());
        } else {
            //
            // A send transaction can not be deleted if it has been included in a block
            //
            SendTransaction sendTx = (SendTransaction)tx;
            int depth = Parameters.wallet.getTxDepth(sendTx.getTxHash());
            if (depth > 0) {
                JOptionPane.showMessageDialog(this, "The transaction has been included in a block",
                                              "Error", JOptionPane.ERROR_MESSAGE);
                return false;
            }
            Parameters.wallet.setSendTxDelete(sendTx.getTxHash(), true);
            tableModel.deleteTransaction(row);
            walletBalance = walletBalance.add(sendTx.getValue()).add(sendTx.getFee());
        }
        return true;
    }

    /**
     * Construct the wallet balance text
     */
    private String getWalletText() {
        return String.format("<html><h2>Wallet %s BTC</h2></html>", Main.satoshiToString(walletBalance));
    }

    /**
     * Construct the safe balance text
     */
    private String getSafeText() {
        return String.format("<html><h2>Safe %s BTC</h2></html>", Main.satoshiToString(safeBalance));
    }

    /**
     * Transaction table model
     */
    private class TransactionTableModel extends AbstractTableModel {

        /** Column names */
        private String[] columnNames;

        /** Column classes */
        private Class<?>[] columnClasses;

        /** Wallet transactions */
        private List<WalletTransaction> txList = new LinkedList<>();

        /**
         * Create the transaction table model
         *
         * @param       columnName          Column names
         * @param       columnClasses       Column classes
         */
        public TransactionTableModel(String[] columnNames, Class<?>[] columnClasses) {
            super();
            if (columnNames.length != columnClasses.length)
                throw new IllegalArgumentException("Number of names not same as number of classes");
            this.columnNames = columnNames;
            this.columnClasses = columnClasses;
            buildTxList();
        }

        /**
         * Build the wallet transaction list and update the balances
         */
        private void buildTxList() {
            txList.clear();
            walletBalance = BigInteger.ZERO;
            safeBalance = BigInteger.ZERO;
            try {
                List<SendTransaction> sendList = Parameters.wallet.getSendTxList();
                for (SendTransaction sendTx : sendList) {
                    long txTime = sendTx.getTxTime();
                    walletBalance = walletBalance.subtract(sendTx.getValue()).subtract(sendTx.getFee());
                    boolean added = false;
                    for (int i=0; i<txList.size(); i++) {
                        if (txList.get(i).getTxTime() <= txTime) {
                            txList.add(i, sendTx);
                            added = true;
                            break;
                        }
                    }
                    if (!added)
                        txList.add(sendTx);
                }
                List<ReceiveTransaction> rcvList = Parameters.wallet.getReceiveTxList();
                for (ReceiveTransaction rcvTx : rcvList) {
                    if (rcvTx.isChange())
                        continue;
                    if (rcvTx.inSafe())
                        safeBalance = safeBalance.add(rcvTx.getValue());
                    else
                        walletBalance = walletBalance.add(rcvTx.getValue());
                    long txTime = rcvTx.getTxTime();
                    boolean added = false;
                    for (int i=0; i<txList.size(); i++) {
                        if (txList.get(i).getTxTime() <= txTime) {
                            txList.add(i, rcvTx);
                            added = true;
                            break;
                        }
                    }
                    if (!added)
                        txList.add(rcvTx);
                }
            } catch (WalletException exc) {
                Main.logException("Unable to build transaction list", exc);
            }
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
            return txList.size();
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
            if (row >= txList.size())
                throw new IndexOutOfBoundsException("Table row "+row+" is not valid");
            Object value;
            WalletTransaction tx = txList.get(row);
            //
            // Get the value for the requested cell
            //
            switch (column) {
                case 0:                                 // Date
                    value = new Date(tx.getTxTime()*1000);
                    break;
                case 1:                                 // Transaction ID
                    value = tx.getTxHash().toString();
                    break;
                case 2:                                 // Type
                    if (tx instanceof ReceiveTransaction)
                        value = "Received with";
                    else
                        value = "Sent to";
                    break;
                case 3:                                 // Name
                    value = null;
                    Address addr = tx.getAddress();
                    if (tx instanceof ReceiveTransaction) {
                        for (ECKey chkKey : Parameters.keys) {
                            if (Arrays.equals(chkKey.getPubKeyHash(), addr.getHash())) {
                                if (chkKey.getLabel().length() > 0)
                                    value = chkKey.getLabel();
                                break;
                            }
                        }
                    } else {
                        for (Address chkAddr : Parameters.addresses) {
                            if (Arrays.equals(chkAddr.getHash(), addr.getHash())) {
                                if (chkAddr.getLabel().length() > 0)
                                    value = chkAddr.getLabel();
                                break;
                            }
                        }
                    }
                    if (value == null)
                        value = addr.toString();
                    break;
                case 4:                                 // Amount
                    value = tx.getValue();
                    break;
                case 5:                                 // Fee
                    if (tx instanceof SendTransaction)
                        value = ((SendTransaction)tx).getFee();
                    else
                        value = null;
                    break;
                case 6:                                 // Location
                    if (tx instanceof ReceiveTransaction) {
                        if (((ReceiveTransaction)tx).inSafe())
                            value = "Safe";
                        else
                            value = "Wallet";
                    } else {
                        value = "";
                    }
                    break;
                case 7:                                 // Status
                    try {
                        int depth = Parameters.wallet.getTxDepth(tx.getTxHash());
                        if ((tx instanceof ReceiveTransaction) && ((ReceiveTransaction)tx).isCoinBase()) {
                            if (depth == 0)
                                value = "Pending";
                            else if (depth < Parameters.COINBASE_MATURITY)
                                value = "Immature";
                            else
                                value = "Mature";
                        } else if (depth == 0) {
                            value = "Pending";
                        } else if (depth < Parameters.TRANSACTION_CONFIRMED) {
                            value = "Building";
                        } else {
                            value = "Confirmed";
                        }
                    } catch (WalletException exc) {
                        Main.logException("Unable to get transaction depth", exc);
                        value = "Unknown";
                    }
                    break;
                default:
                    throw new IndexOutOfBoundsException("Table column "+column+" is not valid");
            }
                return value;
        }

        /**
         * Processes a wallet change
         */
        public void walletChanged() {
            buildTxList();
            fireTableDataChanged();
        }

        /**
         * Returns the wallet transaction for the specified table model row
         *
         * @param       row             Table model row
         * @return                      Wallet transaction
         */
        public WalletTransaction getTransaction(int row) {
            return txList.get(row);
        }

        /**
         * Deletes a wallet transaction
         *
         * @param       row             Table model row
         */
        public void deleteTransaction(int row) {
            txList.remove(row);
            fireTableRowsDeleted(row, row);
        }
    }
}
