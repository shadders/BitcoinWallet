/**
 * Copyright 2013-2014 Ronald W Hoffman
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * This is the main application window
 */
public final class MainWindow extends JFrame implements ActionListener, ConnectionListener, WalletListener {

    /** Create our logger */
    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    /** Main window is minimized */
    private boolean windowMinimized = false;

    /** Synchronizing title set */
    private boolean synchronizingTitle = false;

    /** Rebroadcast pending transactions */
    private boolean txBroadcastDone = false;

    /** Transaction panel */
    private TransactionPanel transactionPanel;

    /**
     * Create the application window
     */
    public MainWindow() {

        //
        // Create the frame
        //
        super("Bitcoin Wallet");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        //
        // Position the window using the saved position from the last time
        // the program was run
        //
        int frameX = 320;
        int frameY = 10;
        String propValue = Main.properties.getProperty("window.main.position");
        if (propValue != null) {
            int sep = propValue.indexOf(',');
            frameX = Integer.parseInt(propValue.substring(0, sep));
            frameY = Integer.parseInt(propValue.substring(sep+1));
        }
        setLocation(frameX, frameY);
        //
        // Size the window using the saved size from the last time
        // the program was run
        //
        int frameWidth = 640;
        int frameHeight = 580;
        propValue = Main.properties.getProperty("window.main.size");
        if (propValue != null) {
            int sep = propValue.indexOf(',');
            frameWidth = Math.max(frameWidth, Integer.parseInt(propValue.substring(0, sep)));
            frameHeight = Math.max(frameHeight, Integer.parseInt(propValue.substring(sep+1)));
        }
        setPreferredSize(new Dimension(frameWidth, frameHeight));
        //
        // Create the application menu bar
        //
        JMenuBar menuBar = new JMenuBar();
        menuBar.setOpaque(true);
        menuBar.setBackground(new Color(230,230,230));
        //
        // Add the "File" menu to the menu bar
        //
        // The "File" menu contains "Exit"
        //
        JMenu menu;
        JMenuItem menuItem;
        menu = new JMenu("File");

        menuItem = new JMenuItem("Exit");
        menuItem.setActionCommand("exit");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuBar.add(menu);
        //
        // Add the "View" menu to the menu bar
        //
        // The "View" menu contains "Receive Addresses" and "Send Addresses"
        //
        menu = new JMenu("View");

        menuItem = new JMenuItem("Receive Addresses");
        menuItem.setActionCommand("view receive");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuItem = new JMenuItem("Send Addresses");
        menuItem.setActionCommand("view send");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuBar.add(menu);
        //
        // Add the "Actions" menu to the menu bar
        //
        // The "Actions" menu contains "Send Coins", "Sign Message" and "Verify Message"
        //
        menu = new JMenu("Actions");

        menuItem = new JMenuItem("Send Coins");
        menuItem.setActionCommand("send coins");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuItem = new JMenuItem("Sign Message");
        menuItem.setActionCommand("sign message");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuItem = new JMenuItem("Verify Message");
        menuItem.setActionCommand("verify message");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuBar.add(menu);
        //
        // Add the "Tools" menu to the menu bar
        //
        // The "Tools" menu contains "Export Keys", "Import Keys" and "Rescan Block Chain"
        //
        menu = new JMenu("Tools");

        menuItem = new JMenuItem("Export Keys");
        menuItem.setActionCommand("export keys");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuItem = new JMenuItem("Import Keys");
        menuItem.setActionCommand("import keys");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuItem = new JMenuItem("Rescan Block Chain");
        menuItem.setActionCommand("rescan");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuBar.add(menu);
        //
        // Add the "Help" menu to the menu bar
        //
        // The "Help" menu contains "About"
        //
        menu = new JMenu("Help");

        menuItem = new JMenuItem("About");
        menuItem.setActionCommand("about");
        menuItem.addActionListener(this);
        menu.add(menuItem);

        menuBar.add(menu);
        //
        // Add the menu bar to the window frame
        //
        setJMenuBar(menuBar);
        //
        // Set up the transaction pane
        //
        transactionPanel = new TransactionPanel(this);
        setContentPane(transactionPanel);
        //
        // Indicate synchronizing with network if we are down-level
        //
        if (Parameters.networkChainHeight > Parameters.wallet.getChainHeight()) {
            setTitle("Bitcoin Wallet - Synchronizing with network");
            synchronizingTitle = true;
        }
        //
        // Receive WindowListener events
        //
        addWindowListener(new ApplicationWindowListener(this));
        //
        // Receive connection events
        //
        Parameters.networkHandler.addListener(this);
        //
        // Receive wallet events
        //
        Parameters.databaseHandler.addListener(this);
    }

    /**
     * Connection started (ConnectionListener interface)
     *
     * @param       peer            Peer node
     */
    @Override
    public void connectionStarted(Peer peer) {
        //
        // Indicate we are synchronizing with the network if we are down-level
        //
        if (!synchronizingTitle && Parameters.networkChainHeight > Parameters.wallet.getChainHeight()) {
            synchronizingTitle = true;
            javax.swing.SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    setTitle("Bitcoin Wallet - Synchronizing with network");
                }
            });
        }
        //
        // Broadcast pending transactions
        //
        if (!txBroadcastDone) {
            txBroadcastDone = true;
            try {
                List<SendTransaction> sendList = Parameters.wallet.getSendTxList();
                if (!sendList.isEmpty()) {
                    List<Sha256Hash> invList = new ArrayList<>(sendList.size());
                    for (SendTransaction sendTx : sendList) {
                        int depth = Parameters.wallet.getTxDepth(sendTx.getTxHash());
                        if (depth == 0)
                            invList.add(sendTx.getTxHash());
                    }
                    if (!invList.isEmpty()) {
                        Message invMsg = InventoryMessage.buildInventoryMessage(peer, Parameters.INV_TX, invList);
                        Parameters.networkHandler.sendMessage(invMsg);
                        log.info(String.format("Pending transaction inventory sent to %s",
                                               peer.getAddress().toString()));
                    }
                }
            } catch (WalletException exc) {
                Main.logException("Unable to get send transaction list", exc);
            }
        }
    }

    /**
     * Connection ended (ConnectionListener interface)
     *
     * @param       peer            Peer node
     */
    @Override
    public void connectionEnded(Peer peer) {
    }

    /**
     * Notification when a block is added to the chain (WalletListener interface)
     *
     * @param       blockHeader     Block header
     */
    @Override
    public void addChainBlock(BlockHeader blockHeader) {
        //
        // Update the table status column for the new chain depth.  Indicate we are
        // no longer synchronizing with the network if we are now caught up.
        //
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                transactionPanel.statusChanged();
                if (synchronizingTitle && Parameters.networkChainHeight <= Parameters.wallet.getChainHeight()) {
                    synchronizingTitle = false;
                    setTitle("Bitcoin Wallet");
                }
            }
        });
    }

    /**
     * Notification when one or more transactions have been updated (WalletListener interface)
     */
    @Override
    public void txUpdated() {
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                transactionPanel.walletChanged();
            }
        });
    }

    /**
     * Action performed (ActionListener interface)
     *
     * @param       ae              Action event
     */
    @Override
    public void actionPerformed(ActionEvent ae) {
        //
        // "about"              - Display information about this program
        // "exit"               - Exit the program
        // "export keys"        - Export private keys
        // "import keys"        - Import private keys
        // "rescan"             - Rescan the block chain
        // "send coins"         - Send coins to a bitcoin address
        // "sign message"       - Sign message
        // "view receive"       - Display the receive addresses
        // "view send"          - Display the send address
        // "verify message"     - Verify message
        //
        try {
            String action = ae.getActionCommand();
            switch (action) {
                case "exit":
                    exitProgram();
                    break;
                case "about":
                    aboutMyWallet();
                    break;
                case "view receive":
                    ReceiveAddressDialog.showDialog(this);
                    transactionPanel.statusChanged();
                    break;
                case "view send":
                    SendAddressDialog.showDialog(this);
                    transactionPanel.statusChanged();
                    break;
                case "send coins":
                    SendDialog.showDialog(this);
                    break;
                case "sign message":
                    if (Parameters.keys.isEmpty())
                        JOptionPane.showMessageDialog(this, "There are no keys defined", "Error",
                                                      JOptionPane.ERROR_MESSAGE);
                    else
                        SignDialog.showDialog(this);
                    break;
                case "verify message":
                    VerifyDialog.showDialog(this);
                    break;
                case "export keys":
                    exportPrivateKeys();
                    break;
                case "import keys":
                    importPrivateKeys();
                    break;
                case "rescan":
                    rescan();
                    break;
            }
        } catch (IOException exc) {
            Main.logException("Unable to process key file", exc);
        } catch (AddressFormatException exc) {
            Main.logException("Key format is not valid", exc);
        } catch (WalletException exc) {
            Main.logException("Unable to perform database operation", exc);
        } catch (Exception exc) {
            Main.logException("Exception while processing action event", exc);
        }
    }

    /**
     * Export keys as Base58-encoded strings (compatible with the Bitcoin-Qt client)
     *
     * The keys will be written to the BitcoinWallet.keys file in the following format:
     *   Label: <text>
     *   Time: <creation-time>
     *   Address: <bitcoin-address>
     *   Private: <private-key>
     *
     * @throws      IOException         Unable to create export file
     */
    private void exportPrivateKeys() throws IOException {
        StringBuilder keyText = new StringBuilder(256);
        File keyFile = new File(Main.dataPath+Main.fileSeparator+"BitcoinWallet.keys");
        if (keyFile.exists())
            keyFile.delete();
        //
        // Write the keys to BitcoinWallet.keys
        //
        try (BufferedWriter out = new BufferedWriter(new FileWriter(keyFile))) {
            for (ECKey key : Parameters.keys) {
                String address = key.toAddress().toString();
                DumpedPrivateKey dumpedKey = key.getPrivKeyEncoded();
                keyText.append("Label:");
                keyText.append(key.getLabel());
                keyText.append("\nTime:");
                keyText.append(Long.toString(key.getCreationTime()));
                keyText.append("\nAddress:");
                keyText.append(address);
                keyText.append("\nPrivate:");
                keyText.append(dumpedKey.toString());
                keyText.append("\n\n");
                out.write(keyText.toString());
                keyText.delete(0,keyText.length());
            }
        }
        JOptionPane.showMessageDialog(this, "Keys exported to BitcoinWallet.keys", "Keys Exported",
                                      JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Import private keys
     *
     * The keys will be read from the BitcoinWallet.keys file.  The keys must be in the format created by
     * exportPrivateKeys().  Blank lines and lines beginning with '#' will be ignored.  Lines containing
     * unrecognized prefixes will also be ignored.
     *
     * @throws      AddressFormatException      Address format is not valid
     * @throws      IOException                 Unable to read file
     * @throws      WalletException             Unable to update database
     */
    private void importPrivateKeys() throws IOException, AddressFormatException, WalletException {
        File keyFile = new File(Main.dataPath+Main.fileSeparator+"BitcoinWallet.keys");
        if (!keyFile.exists()) {
            JOptionPane.showMessageDialog(this, "BitcoinWallet.keys does not exist",
                                          "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        //
        // Read each line from the key file
        //
        try (BufferedReader in = new BufferedReader(new FileReader(keyFile))) {
            String line;
            String importedLabel = "";
            String importedTime = "";
            String importedAddress = "";
            String encodedPrivateKey = "";
            boolean foundKey = false;
            while ((line=in.readLine()) != null) {
                //
                // Remove leading and trailing whitespace
                //
                line = line.trim();
                //
                // Skip comment lines and blank lines
                //
                if (line.length() == 0 || line.charAt(0) == '#')
                    continue;
                int sep = line.indexOf(':');
                if (sep <1 || line.length() == sep+1)
                    continue;
                //
                // Parse the line formatted as "keyword:value".  The following keywords are supported and
                // must appear in the listed order:
                //    Label = Name assigned to the key (may be omitted)
                //    Time = Key creation time (may be omitted)
                //    Address = Bitcoin address for the key (may be omitted)
                //    Private = Private key (must be specified and must be the last line for the key)
                //
                String keyword = line.substring(0, sep);
                String value = line.substring(sep+1);
                switch (keyword) {
                    case "Label":
                        importedLabel = value;
                        break;
                    case "Time":
                        importedTime = value;
                        break;
                    case "Address":
                        importedAddress = value;
                        break;
                    case "Private":
                        encodedPrivateKey = value;
                        foundKey = true;
                        break;
                }
                //
                // Add the key to the wallet and update the bloom filter
                //
                if (foundKey) {
                    DumpedPrivateKey dumpedKey = new DumpedPrivateKey(encodedPrivateKey);
                    ECKey key = dumpedKey.getKey();
                    if (importedAddress.equals(key.toAddress().toString())) {
                        key.setLabel(importedLabel);
                        key.setCreationTime(Long.parseLong(importedTime));
                        if (!Parameters.keys.contains(key)) {
                            Parameters.wallet.storeKey(key);
                            synchronized(Parameters.lock) {
                                boolean added = false;
                                for (int i=0; i<Parameters.keys.size(); i++) {
                                    if (Parameters.keys.get(i).getLabel().compareToIgnoreCase(importedLabel) > 0) {
                                        Parameters.keys.add(i, key);
                                        added = true;
                                        break;
                                    }
                                }
                                if (!added)
                                    Parameters.keys.add(key);
                                Parameters.bloomFilter.insert(key.getPubKey());
                                Parameters.bloomFilter.insert(key.getPubKeyHash());
                            }
                        }
                    } else {
                        JOptionPane.showMessageDialog(this,
                                String.format("Address %s does not match imported private key", importedAddress),
                                "Error", JOptionPane.ERROR_MESSAGE);
                    }
                    //
                    // Reset for the next key
                    //
                    foundKey = false;
                    importedLabel = "";
                    importedTime = "";
                    importedAddress = "";
                    encodedPrivateKey = "";
                }
            }
        }
        JOptionPane.showMessageDialog(this, "Keys imported from BitcoinWallet.keys", "Keys Imported",
                                      JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Rescan the block chain
     *
     * @throws      WalletException     Unable to scan block chain
     */
    private void rescan() throws WalletException {
        //
        // Get the earliest key creation time
        //
        long creationTime = System.currentTimeMillis()/1000;
        for (ECKey key : Parameters.keys)
            creationTime = Math.min(creationTime, key.getCreationTime());
        //
        // Initiate a block chain rescan
        //
        log.info(String.format("Block chain rescan started from time %d", creationTime));
        Parameters.databaseHandler.rescanChain(creationTime);
    }

    /**
     * Exit the application
     *
     * @exception       IOException     Unable to save application data
     */
    private void exitProgram() throws IOException {

        //
        // Remember the current window position and size unless the window
        // is minimized
        //
        if (!windowMinimized) {
            Point p = getLocation();
            Dimension d = getSize();
            Main.properties.setProperty("window.main.position", p.x+","+p.y);
            Main.properties.setProperty("window.main.size", d.width+","+d.height);
        }
        //
        // All done
        //
        Main.shutdown();
    }

    /**
     * Display information about the BitcoinWallet application
     */
    private void aboutMyWallet() {
        StringBuilder info = new StringBuilder(256);
        info.append("<html>BitcoinWallet Version 1.1<br>");
        info.append("Copyright 2013-2014 Ronald W Hoffman. All rights reserved.<br>");

        info.append("<br>User name: ");
        info.append((String)System.getProperty("user.name"));

        info.append("<br>Home directory: ");
        info.append((String)System.getProperty("user.home"));

        info.append("<br><br>OS: ");
        info.append((String)System.getProperty("os.name"));

        info.append("<br>OS version: ");
        info.append((String)System.getProperty("os.version"));

        info.append("<br>OS patch level: ");
        info.append((String)System.getProperty("sun.os.patch.level"));

        info.append("<br><br>Java vendor: ");
        info.append((String)System.getProperty("java.vendor"));

        info.append("<br>Java version: ");
        info.append((String)System.getProperty("java.version"));

        info.append("<br>Java home directory: ");
        info.append((String)System.getProperty("java.home"));

        info.append("<br>Java class path: ");
        info.append((String)System.getProperty("java.class.path"));

        info.append("<br><br>Current Java memory usage: ");
        info.append(String.format("%,.3f MB", (double)Runtime.getRuntime().totalMemory()/(1024.0*1024.0)));

        info.append("<br>Maximum Java memory size: ");
        info.append(String.format("%,.3f MB", (double)Runtime.getRuntime().maxMemory()/(1024.0*1024.0)));

        info.append("</html>");
        JOptionPane.showMessageDialog(this, info.toString(), "About BitcoinWallet",
                                      JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Listen for window events
     */
    private class ApplicationWindowListener extends WindowAdapter {

        /** Application window */
        private JFrame window;

        /**
         * Create the window listener
         *
         * @param       window      The application window
         */
        public ApplicationWindowListener(JFrame window) {
            this.window = window;
        }

        /**
         * Window has been minimized (WindowListener interface)
         *
         * @param       we              Window event
         */
        @Override
        public void windowIconified(WindowEvent we) {
            windowMinimized = true;
        }

        /**
         * Window has been restored (WindowListener interface)
         *
         * @param       we              Window event
         */
        @Override
        public void windowDeiconified(WindowEvent we) {
            windowMinimized = false;
        }

        /**
         * Window is closing (WindowListener interface)
         *
         * @param       we              Window event
         */
        @Override
        public void windowClosing(WindowEvent we) {
            try {
                exitProgram();
            } catch (Exception exc) {
                Main.logException("Exception while closing application window", exc);
            }
        }
    }
}
