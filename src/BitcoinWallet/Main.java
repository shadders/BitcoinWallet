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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

import java.math.BigDecimal;
import java.math.BigInteger;

import java.net.UnknownHostException;

import java.nio.channels.FileLock;

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
 * <li>Specify PROD to use the production Bitcoin network or TEST to use the regression test network.</li>
 * <li>Bitcoin URI when invoking BitcoinWallet from a web browser to handle a payment request.
 * The URI follows the PROD or TEST argument and must not contain any spaces.  This means the
 * URI must be registered to use the original URI encoding as received over the internet.</li>
 * </ul>
 *
 * <p>The following command-line options can be specified:</p>
 * <table>
 * <col width=30%/>
 * <col width=70%/>
 * <tr><td>-Dbitcoin.datadir=directory-path</td>
 * <td>Specifies the application data directory.  Application data will be stored in
 * a system-specific default directory if no data directory is specified:
 *      <ul>
 *      <li>Linux: user-home/.BitcoinWallet</li>
 *      <li>Mac: user-home/Library/Application Support/BitcoinWallet</li>
 *      <li>Windows: user-home\AppData\Roaming\BitcoinWallet</li>
 *      </ul>
 * </td></tr>
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
 *
 * <p>The following configuration options can be specified in BitcoinWallet.conf.  Blank lines and lines beginning
 * with '#' are ignored.</p>
 * <table>
 * <col width=30%/>
 * <col width=70%/>
 * <tr><td>connect=[address]:port</td>
 * <td>Connect to the specified peer.  The connect option can be repeated to connect to multiple peers.
 * If one or more connect options are specified, connections will be created to just the listed peers.
 * If no connect option is specified, DNS discovery will be used along with the broadcast peer addresses to create
 * outbound connections.</td></tr>
 * </table>
 */
public class Main {

    /** Logger instance */
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /** Conversion value for BTC to Satoshi (1 Satoshi = 0.00000001 BTC) */
    private static final BigDecimal SATOSHI = new BigDecimal("100000000");

    /** File separator */
    public static String fileSeparator;

    /** Line separator */
    public static String lineSeparator;

    /** User home */
    public static String userHome;

    /** Operating system */
    public static String osName;

    /** Application lock file */
    private static RandomAccessFile lockFile;

    /** Application lock */
    private static FileLock fileLock;

    /** Application properties */
    public static Properties properties;

    /** Data directory */
    public static String dataPath;

    /** Application properties file */
    private static File propFile;

    /** Test network */
    private static boolean testNetwork = false;

    /** Bitcoin URI */
    private static String uriString;

    /** Main application window */
    public static MainWindow mainWindow;

    /** Message handler */
    private static MessageHandler messageHandler;

    /** Peer address */
    private static PeerAddress[] peerAddresses;

    /** Thread group */
    private static ThreadGroup threadGroup;

    /** Worker threads */
    private static final List<Thread> threads = new ArrayList<>(5);

    /** Deferred exception text */
    private static String deferredText;

    /** Deferred exception */
    private static Throwable deferredException;

    /**
     * Handles program initialization
     *
     * @param   args                Command-line arguments
     */
    public static void main(String[] args) {
        try {
            fileSeparator = System.getProperty("file.separator");
            lineSeparator = System.getProperty("line.separator");
            userHome = System.getProperty("user.home");
            osName = System.getProperty("os.name").toLowerCase();
            //
            // Process command-line options
            //
            dataPath = System.getProperty("bitcoin.datadir");
            if (dataPath == null) {
                if (osName.startsWith("win"))
                    dataPath = userHome+"\\Appdata\\Roaming\\BitcoinWallet";
                else if (osName.startsWith("linux"))
                    dataPath = userHome+"/.BitcoinWallet";
                else if (osName.startsWith("mac os"))
                    dataPath = userHome+"/Library/Application Support/BitcoinWallet";
                else
                    dataPath = userHome+"/BitcoinWallet";
            }
            //
            // Process command-line arguments
            //
            if (args.length != 0)
                processArguments(args);
            if (testNetwork)
                dataPath = dataPath+fileSeparator+"TestNet";
            //
            // Create the data directory if it doesn't exist
            //
            File dirFile = new File(dataPath);
            if (!dirFile.exists())
                dirFile.mkdirs();
            //
            // Initialize the logging properties from 'logging.properties'
            //
            File logFile = new File(dataPath+fileSeparator+"logging.properties");
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
            // Open the application lock file
            //
            lockFile = new RandomAccessFile(dataPath+fileSeparator+".lock", "rw");
            fileLock = lockFile.getChannel().tryLock();
            if (fileLock == null)
                throw new IllegalStateException("BitcoinWallet is already running");
            //
            // Process configuration file options
            //
            processConfig();
            if (testNetwork && peerAddresses == null)
                throw new IllegalArgumentException("You must specify at least one peer for the test network");
            //
            // Initialize the network parameters
            //
            String genesisName;
            if (testNetwork) {
                Parameters.MAGIC_NUMBER = Parameters.MAGIC_NUMBER_TESTNET;
                Parameters.ADDRESS_VERSION = Parameters.ADDRESS_VERSION_TESTNET;
                Parameters.DUMPED_PRIVATE_KEY_VERSION = Parameters.DUMPED_PRIVATE_KEY_VERSION_TESTNET;
                Parameters.GENESIS_BLOCK_HASH = Parameters.GENESIS_BLOCK_TESTNET;
                Parameters.GENESIS_BLOCK_TIME = Parameters.GENESIS_TIME_TESTNET;
                Parameters.MAX_TARGET_DIFFICULTY = Parameters.MAX_DIFFICULTY_TESTNET;
                genesisName = "GenesisBlock/GenesisBlockTest.dat";
            } else {
                Parameters.MAGIC_NUMBER = Parameters.MAGIC_NUMBER_PRODNET;
                Parameters.ADDRESS_VERSION = Parameters.ADDRESS_VERSION_PRODNET;
                Parameters.DUMPED_PRIVATE_KEY_VERSION = Parameters.DUMPED_PRIVATE_KEY_VERSION_PRODNET;
                Parameters.GENESIS_BLOCK_HASH = Parameters.GENESIS_BLOCK_PRODNET;
                Parameters.GENESIS_BLOCK_TIME = Parameters.GENESIS_TIME_PRODNET;
                Parameters.MAX_TARGET_DIFFICULTY = Parameters.MAX_DIFFICULTY_PRODNET;
                genesisName = "GenesisBlock/GenesisBlockProd.dat";
            }
            Parameters.PROOF_OF_WORK_LIMIT = Utils.decodeCompactBits(Parameters.MAX_TARGET_DIFFICULTY);
            //
            // Load the genesis block
            //
            Class<?> mainClass = Class.forName("BitcoinWallet.Main");
            InputStream classStream = mainClass.getClassLoader().getResourceAsStream(genesisName);
            if (classStream == null)
                throw new IllegalStateException("Genesis block resource not found");
            Parameters.GENESIS_BLOCK_BYTES = new byte[classStream.available()];
            classStream.read(Parameters.GENESIS_BLOCK_BYTES);
            //
            // Load the saved application properties
            //
            propFile = new File(dataPath+fileSeparator+"BitcoinWallet.properties");
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
                @Override
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
            if (Parameters.passPhrase == null || Parameters.passPhrase.length() == 0) {
                Parameters.passPhrase = JOptionPane.showInputDialog("Enter the wallet passphrase");
                if (Parameters.passPhrase == null || Parameters.passPhrase.length() == 0)
                    System.exit(0);
            }
            //
            // Create the wallet
            //
            Parameters.wallet = new WalletLdb(dataPath);
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
            // Process a payment request
            //
            if (uriString != null) {
                log.info(String.format("Bitcoin URI: %s", uriString));
                try {
                    BitcoinURI uri = new BitcoinURI(uriString);
                    BitcoinPayment request = new BitcoinPayment(uri);
                    String network = request.getNetwork();
                    if ((network.equals("main") && testNetwork) ||
                                    (network.equals("test") && !testNetwork))
                        throw new BitcoinPaymentException("Payment request received from the wrong network");
                    StringBuilder builder = new StringBuilder(128);
                    builder.append("Do you want to send ").append(satoshiToString(request.getAmount())).append(" BTC?");
                    String merchant = request.getMerchantName();
                    builder.append("\nTo: ").append(merchant.length()>0?merchant:"<Unknown>");
                    String memo = request.getRequestMemo();
                    if (memo.length() > 0)
                        builder.append("\nFor: ").append(memo);
                    int option = JOptionPane.showConfirmDialog(null, builder.toString(),
                                            "Send Coin", JOptionPane.YES_NO_OPTION);
                    if (option == JOptionPane.YES_OPTION) {
                        request.sendCoins();
                        memo = request.getAckMemo();
                        builder = new StringBuilder(128);
                        builder.append("Payment has been sent");
                        if (memo.length() > 0)
                            builder.append("\nReceipt: ").append(memo);
                        JOptionPane.showMessageDialog(null, builder.toString(),
                                                      "Coins Sent", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (AddressFormatException exc) {
                    log.error("Invalid payment address specified", exc);
                    JOptionPane.showMessageDialog(null,
                                                  "Invalid payment address specified\n"+exc.getMessage(),
                                                  "Error", JOptionPane.ERROR_MESSAGE);
                } catch (BitcoinURIException exc) {
                    log.error("Invalid bitcoin URI specified", exc);
                    JOptionPane.showMessageDialog(null,
                                                  "Invalid bitcoin URI specified\n"+exc.getMessage(),
                                                  "Error", JOptionPane.ERROR_MESSAGE);
                } catch (BitcoinPaymentException exc) {
                    log.error("Invalid bitcoin payment reuest", exc);
                    JOptionPane.showMessageDialog(null,
                                                  "Invalid bitcoin payment request\n"+exc.getMessage(),
                                                  "Error", JOptionPane.ERROR_MESSAGE);
                } catch (InsufficientFeeException exc) {
                    JOptionPane.showMessageDialog(null, "There are not enough confirmed coins available",
                                                  "Error", JOptionPane.ERROR_MESSAGE);
                } catch (Exception exc) {
                    log.error("Runtime exception while processing payment request", exc);
                    JOptionPane.showMessageDialog(null,
                                      "Runtime exception while processing payment request\n"+exc.getMessage(),
                                      "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
            //
            // Start the GUI
            //
            createAndShowGUI();
        } catch (KeyException exc) {
            log.error("The wallet passphrase is not correct", exc);
            JOptionPane.showMessageDialog(null, "The wallet passphrase is not correct",
                                          "Error", JOptionPane.ERROR_MESSAGE);
            shutdown();
        } catch (Exception exc) {
            logException("Exception while starting wallet services", exc);
            shutdown();
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
        // Close the application lock file
        //
        try {
            fileLock.release();
            lockFile.close();
        } catch (IOException exc) {
        }
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
        // A bitcoin URI will be specified if we are processing a payment request
        //
        if (args.length > 1) {
            if (args[1].startsWith("bitcoin:"))
                uriString = args[1];
            else
                throw new IllegalArgumentException("Unrecognized command line parameter");
        }
    }

    /**
     * Process the configuration file
     *
     * @throws      IllegalArgumentException    Invalid configuration option
     * @throws      IOException                 Unable to read configuration file
     * @throws      UnknownHostException        Invalid peer address specified
     */
    private static void processConfig() throws IOException, IllegalArgumentException, UnknownHostException {
        //
        // Use the defaults if there is no configuration file
        //
        File configFile = new File(dataPath+Main.fileSeparator+"BitcoinWallet.conf");
        if (!configFile.exists())
            return;
        //
        // Process the configuration file
        //
        List<PeerAddress> addressList = new ArrayList<>(5);
        try (BufferedReader in = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line=in.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.charAt(0) == '#')
                    continue;
                int sep = line.indexOf('=');
                if (sep < 1)
                    throw new IllegalArgumentException(String.format("Invalid configuration option: %s", line));
                String option = line.substring(0, sep).trim().toLowerCase();
                String value = line.substring(sep+1).trim();
                switch (option) {
                    case "connect":
                        PeerAddress addr = new PeerAddress(value);
                        addressList.add(addr);
                        break;
                    case "passphrase":
                        Parameters.passPhrase = value;
                        break;
                    default:
                        throw new IllegalArgumentException(String.format("Invalid configuration option: %s", line));
                }
            }
        }
        if (!addressList.isEmpty())
            peerAddresses = addressList.toArray(new PeerAddress[addressList.size()]);
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
                    @Override
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
