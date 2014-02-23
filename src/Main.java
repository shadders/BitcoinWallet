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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import java.math.BigDecimal;
import java.math.BigInteger;

import java.net.InetAddress;
import java.net.UnknownHostException;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.LogManager;

import javax.swing.*;

/**
 * <p>BitcoinWallet is a wallet used for sending and receiving Bitcoins.  It supports labels
 * for send and receive addresses as well as a transaction safe.  Transactions in the safe
 * will not be used to create new transactions (that is, the bitcoins represented by these
 * transactions will not be spent).</p>
 *
 * <p>The main() method is invoked by the JVM to start the application.</p>
 *
 * <p>If no command-line arguments are provided, we will connect to the production Bitcoin network
 * using DNS discovery.</p>
 *
 * <p>The following command-line arguments are supported:</p>
 * <ul>
 * <li>Specify PROD to use the production Bitcoin network or TEST to use the test network.  Files for the production
 * network are stored in ~\AppData\Roaming\BitcoinWallet while files for the test network are stored in
 * ~\AppData\Roaming\BitcoinWallet\TestNet.</li>
 * <li>Specify the IP address and port of the first peer node that we will use (address:port).  If omitted, we will
 * use DNS discovery to locate peer nodes.</li>
 * <li>Additional peer nodes can be specified by adding additional address:port values</li>
 * </ul>
 *
 * <p>The following command-line options can be specified:</p>
 * <table>
 * <col width=30%/>
 * <col width=70%/>
 * <tr><td>-Dbitcoin.datadir=directory-path</td>
 * <td>Specifies the application data directory.  Application data will be stored in
 * ~/AppData/Roaming/BitcoinWallet if no path is specified.</td></tr>
 *
 * <tr><td>-Djava.util.logging.config.file=file-path</td>
 * <td>Specifies the logger configuration file.  The logger properties will be read from 'logging.properties'
 * in the application data directory.  If this file is not found, the 'java.util.logging.config.file' system
 * property will be used to locate the logger configuration file.  If this property is not defined,
 * the logger properties will be obtained from jre/lib/logging.properties.
 *      <ul>
 *      <li>JDK FINE corresponds to the SLF4J DEBUG level</li>
 *      <li>JDK INFO corresponds to the SLF4J INFO level</li>
 *      <li>JDK WARNING corresponds to the SLF4J WARN level</li>
 *      <li>JDK SEVERE corresponds to the SLF4J ERROR level</li>
 *      </ul>
 *  </td></tr>
 * </table>
 */
public class Main {

    /** Logger instance */
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /** Conversion value for BTC to Satoshi (1 Satoshi = 0.00000001 BTC) */
    private static final BigDecimal SATOSHI = new BigDecimal("100000000");

    /** Application properties */
    public static Properties properties;

    /** Data directory */
    public static String dataPath;

    /** Application properties file */
    private static File propFile;

    /** Test network */
    private static boolean testNetwork = false;

    /** Main application window */
    public static MainWindow mainWindow;

    /** Message handler */
    private static MessageHandler messageHandler;

    /** Peer address */
    private static PeerAddress[] peerAddresses;

    /** Thread group */
    private static ThreadGroup threadGroup;

    /** Worker threads */
    private static List<Thread> threads = new ArrayList<>(5);

    /** Deferred exception text */
    private static String deferredText;

    /** Deferred exception */
    private static Throwable deferredException;

    public static void main(String[] args) {
        try {
            //
            // Process command-line options
            //
            dataPath = System.getProperty("bitcoin.datadir");
            if (dataPath == null)
                dataPath = System.getProperty("user.home")+"\\AppData\\Roaming\\BitcoinWallet";
            //
            // Process command-line arguments
            //
            if (args.length != 0)
                processArguments(args);
            //
            // Initialize the application variables
            //
            if (testNetwork)
                dataPath = dataPath+"\\TestNet";
            propFile = new File(dataPath+"\\BitcoinWallet.properties");
            //
            // Create the data directory if it doesn't exist
            //
            File dirFile = new File(dataPath);
            if (!dirFile.exists())
                dirFile.mkdirs();
            //
            // Initialize the logging properties from 'logging.properties'
            //
            File logFile = new File(dataPath+"\\logging.properties");
            if (logFile.exists()) {
                FileInputStream inStream = new FileInputStream(logFile);
                LogManager.getLogManager().readConfiguration(inStream);
            }
            //
            // Use the brief logging format
            //
            BriefLogFormatter.init();
            log.info(String.format("Application data path: '%s'", dataPath));
            //
            // Load the saved application properties
            //
            properties = new Properties();
            if (propFile.exists()) {
                try (FileInputStream in = new FileInputStream(propFile)) {
                    properties.load(in);
                }
            }
            //
            // Start our services on the GUI thread so we can display dialogs
            //
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            javax.swing.SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    startup();
                }
            });
        } catch (Exception exc) {
            logException("Exception during program initialization", exc);
        }
    }

    /**
     * Start our services
     */
    private static void startup() {
        try {
            //
            // Get the wallet passphrase if it is not specified in the application properties
            //
            Parameters.passPhrase = properties.getProperty("passphrase");
            if (Parameters.passPhrase == null || Parameters.passPhrase.length() == 0) {
                Parameters.passPhrase = JOptionPane.showInputDialog("Enter the wallet passphrase");
                if (Parameters.passPhrase == null || Parameters.passPhrase.length() == 0)
                    System.exit(0);
            }
            //
            // Create the wallet.  We will use the 'jwallet' database for the production network
            // and the 'jtestwlt' database for the test network.
            //
            Parameters.wallet = new WalletPg(testNetwork?"jtestwlt":"jwallet");
            //
            // Get the address and key lists
            //
            Parameters.addresses = Parameters.wallet.getAddressList();
            Parameters.keys = Parameters.wallet.getKeyList();
            //
            // Locate the change key and create it if we don't have one yet
            //
            for (ECKey key : Parameters.keys) {
                if (key.isChange()) {
                    Parameters.changeKey = key;
                    break;
                }
            }
            if (Parameters.changeKey == null) {
                ECKey changeKey = new ECKey();
                changeKey.setLabel("<Change>");
                changeKey.setChange(true);
                Parameters.wallet.storeKey(changeKey);
                Parameters.changeKey = changeKey;
                Parameters.keys.add(changeKey);
            }
            //
            // Create our bloom filter
            //
            int elementCount = Parameters.keys.size()*2 + 15;
            BloomFilter filter = new BloomFilter(elementCount);
            for (ECKey key : Parameters.keys) {
                filter.insert(key.getPubKey());
                filter.insert(key.getPubKeyHash());
            }
            Parameters.bloomFilter = filter;
            //
            // Start the worker threads
            //
            // DatabaseListener - 1 thread
            // NetworkListener - 1 thread
            // MessageHandler - 1 thread
            //
            threadGroup = new ThreadGroup("Workers");

            Parameters.databaseHandler = new DatabaseHandler();
            Thread thread = new Thread(threadGroup, Parameters.databaseHandler);
            thread.start();
            threads.add(thread);

            Parameters.networkHandler = new NetworkHandler(peerAddresses);
            thread = new Thread(threadGroup, Parameters.networkHandler);
            thread.start();
            threads.add(thread);

            messageHandler = new MessageHandler();
            thread = new Thread(threadGroup, messageHandler);
            thread.start();
            threads.add(thread);
            //
            // Start the GUI
            //
            createAndShowGUI();
        } catch (KeyException exc) {
            JOptionPane.showMessageDialog(null, "The wallet passphrase is not correct",
                                          "Error", JOptionPane.ERROR_MESSAGE);
        } catch (Exception exc) {
            logException("Exception while starting wallet services", exc);
        }
    }

    /**
     * Create and show our application GUI
     *
     * This method is invoked on the AWT event thread to avoid timing
     * problems with other window events
     */
    private static void createAndShowGUI() {
        //
        // Use the normal window decorations as defined by the look-and-feel
        // schema
        //
        JFrame.setDefaultLookAndFeelDecorated(true);
        //
        // Create the main application window
        //
        mainWindow = new MainWindow();
        //
        // Show the application window
        //
        mainWindow.pack();
        mainWindow.setVisible(true);
    }

    /**
     * Shutdown and exit
     */
    public static void shutdown() {

        //
        // Stop the network
        //
        Parameters.networkHandler.shutdown();
        Parameters.databaseHandler.shutdown();
        messageHandler.shutdown();
        //
        // Wait for threads to terminate
        //
        try {
            log.info("Waiting for worker threads to stop");
            for (Thread thread : threads)
                thread.join(2*60*1000);
            log.info("Worker threads have stopped");
        } catch (InterruptedException exc) {
            log.info("Interrupted while waiting for threads to stop");
        }
        //
        // Close the database
        //
        Parameters.wallet.close();
        //
        // Save the application properties
        //
        saveProperties();
        //
        // All done
        //
        System.exit(0);
    }

    /**
     * Save the application properties
     */
    public static void saveProperties() {
        try {
            try (FileOutputStream out = new FileOutputStream(propFile)) {
                properties.store(out, "BitcoinWallet Properties");
            }
        } catch (Exception exc) {
            Main.logException("Exception while saving application properties", exc);
        }
    }

    /**
     * Parses the command-line arguments
     *
     * @param       args            Command-line arguments
     */
    private static void processArguments(String[] args) throws UnknownHostException {
        //
        // PROD indicates we should use the production network
        // TEST indicates we should use the test network
        //
        if (args[0].equalsIgnoreCase("TEST")) {
            testNetwork = true;
        } else if (!args[0].equalsIgnoreCase("PROD")) {
            throw new IllegalArgumentException("Valid options are PROD and TEST");
        }
        //
        // One or more peer nodes can be specified as addr:port (for example, 127.0.0.1:19000)
        // These nodes will be used instead of using DNS Discovery to find nodes.
        //
        int count = args.length - 1;
        if (count > 0) {
            peerAddresses = new PeerAddress[count];
            for (int i=0; i<count; i++) {
                String[] peerParts = args[i+1].split(":");
                if (peerParts.length != 2)
                    throw new IllegalArgumentException("Incorrect address:port specification: "+args[i+1]);
                String[] addrParts = peerParts[0].split("\\D");
                if (addrParts.length != 4)
                    throw new IllegalArgumentException("Invalid IPv4 address specification: "+peerParts[0]);
                byte[] peerAddr = new byte[4];
                for (int j=0; j<4; j++)
                    peerAddr[j] = (byte)Integer.parseInt(addrParts[j]);
                InetAddress address = InetAddress.getByAddress(peerAddr);
                peerAddresses[i] = new PeerAddress(address, Integer.parseInt(peerParts[1]));
            }
        }
    }

    /**
     * Convert a decimal string to a Satoshi BigInteger (1 Satoshi = 0.00000001 BTC)
     *
     * @param       value           String to be converted
     * @return                      BigInteger representation
     */
    public static BigInteger stringToSatoshi(String value) throws NumberFormatException {
        if (value == null)
            throw new IllegalArgumentException("No string value provided");
        if (value.isEmpty())
            return BigInteger.ZERO;
        BigDecimal decValue = new BigDecimal(value);
        return decValue.multiply(SATOSHI).toBigInteger();
    }

    /**
     * Convert from a Satoshi BigInteger (1 Satoshi = 0.00000001 BTC) to a formatted BTC decimal string.
     * We will keep at least 4 decimal places in the result.
     *
     * @param       value           Value to be converted
     * @return                      A formatted decimal string
     */
    public static String satoshiToString(BigInteger value) {
        //
        // Format the BTC amount
        //
        // BTC values are represented as integer values expressed in Satoshis (1 Satoshi = 0.00000001 BTC)
        //
        BigInteger bvalue = value;
        boolean negative = bvalue.compareTo(BigInteger.ZERO) < 0;
        if (negative)
            bvalue = bvalue.negate();
        //
        // Get the BTC amount as a formatted string with 8 decimal places
        //
        BigDecimal dvalue = new BigDecimal(bvalue, 8);
        String formatted = dvalue.toPlainString();
        //
        // Drop trailing zeroes beyond 4 decimal places
        //
        int decimalPoint = formatted.indexOf(".");
        int toDelete = 0;
        for (int i=formatted.length()-1; i>decimalPoint+4; i--) {
            if (formatted.charAt(i) == '0')
                toDelete++;
            else
                break;
        }
        String text = (negative?"-":"") + formatted.substring(0, formatted.length()-toDelete);
        return text;
    }

    /**
     * Display a dialog when an exception occurs.
     *
     * @param       text        Text message describing the cause of the exception
     * @param       exc         The Java exception object
     */
    public static void logException(String text, Throwable exc) {
        if (SwingUtilities.isEventDispatchThread()) {
            StringBuilder string = new StringBuilder(512);
            //
            // Display our error message
            //
            string.append("<html><b>");
            string.append(text);
            string.append("</b><br><br>");
            //
            // Display the exception object
            //
            string.append(exc.toString());
            string.append("<br>");
            //
            // Display the stack trace
            //
            StackTraceElement[] trace = exc.getStackTrace();
            int count = 0;
            for (StackTraceElement elem : trace) {
                string.append(elem.toString());
                string.append("<br>");
                if (++count == 25)
                    break;
            }
            string.append("</html>");
            JOptionPane.showMessageDialog(Main.mainWindow, string, "Error", JOptionPane.ERROR_MESSAGE);
        } else if (deferredException == null) {
            deferredText = text;
            deferredException = exc;
            try {
                javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
                    public void run() {
                        Main.logException(deferredText, deferredException);
                        deferredException = null;
                        deferredText = null;
                    }
                });
            } catch (Exception logexc) {
                log.error("Unable to log exception during program initialization");
            }
        }
    }

    /**
     * Dumps a byte array to the log
     *
     * @param       text        Text message
     * @param       data        Byte array
     */
    public static void dumpData(String text, byte[] data) {
        dumpData(text, data, 0, data.length);
    }

    /**
     * Dumps a byte array to the log
     *
     * @param       text        Text message
     * @param       data        Byte array
     * @param       length      Length to dump
     */
    public static void dumpData(String text, byte[] data, int length) {
        dumpData(text, data, 0, length);
    }

    /**
     * Dump a byte array to the log
     *
     * @param       text        Text message
     * @param       data        Byte array
     * @param       offset      Offset into array
     * @param       length      Data length
     */
    public static void dumpData(String text, byte[] data, int offset, int length) {
        StringBuilder outString = new StringBuilder(512);
        outString.append(text);
        outString.append("\n");
        for (int i=0; i<length; i++) {
            if (i%32 == 0)
                outString.append(String.format(" %14X  ", i));
            else if (i%4 == 0)
                outString.append(" ");
            outString.append(String.format("%02X", data[offset+i]));
            if (i%32 == 31)
                outString.append("\n");
        }
        if (length%32 != 0)
            outString.append("\n");
        log.info(outString.toString());
    }
}
