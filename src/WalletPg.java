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

import java.io.EOFException;

import java.math.BigInteger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * <p>A Wallet stores block headers, transactions, addresses and keys.  These are used to
 * access bitcoins recorded in the block chain.  A wallet can be deleted and recreated as long
 * as the private keys have been exported and then imported into the new wallet.</p>
 *
 * <p>The Addresses table contains send addresses.</p>
 * <pre>
 *   Column             Definition          Description
 *   ======             ==========          ===========
 *   address            BYTEA               Address
 *   label              VARCHAR(64)         Label
 * </pre>
 *
 * <p>The Keys table contains the public/private key pairs.</p>
 * <pre>
 *   Column             Definition          Description
 *   ======             ==========          ===========
 *   pubKey             BYTEA               Public key
 *   encPrivKey         BYTEA               Encrypted private key
 *   creationTime       BIGINT              Creation time
 *   label              VARCHAR(64)         Label
 *   isChange           BOOLEAN             TRUE if this is a change key
 * </pre>
 *
 * <p>The Headers table contains the block headers that have been received and includes
 * orphan blocks as well as chain blocks.</p>
 * <pre>
 *   Column             Definition          Description
 *   ======             ==========          ===========
 *   blockHash          BYTEA               Block hash
 *   prevHash           BYTEA               Previous block hash
 *   blockTime          BIGINT              Time block was mined
 *   targetDifficulty   BIGINT              Target difficulty for the block
 *   merkleRoot         BYTEA               Merkle root
 *   onChain            BOOLEAN             TRUE if the block is on the block chain
 *   blockHeight        INTEGER             Block height (if block is on block chain)
 *   chainWork          BYTEA               Chain work (if block is on block chain)
 *   matches            BYTEA               Matched transactions for the block
 * </pre>
 *
 * <p>The Received table contains transactions sending coins to the wallet</p>
 * <pre>
 *   Column             Definition          Description
 *   ======             ==========          ===========
 *   normID             BYTEA               Normalized transaction ID
 *   txHash             BYTEA               Output transaction hash
 *   txIndex            INTEGER             Output transaction index
 *   txTime             BIGINT              Transaction timestamp
 *   blockHash          BYTEA               Chain block hash (zero hash if not confirmed yet)
 *   address            BYTEA               Receive address
 *   value              BYTEA               Value
 *   scriptBytes        BYTEA               output script bytes
 *   isSpent            BOOLEAN             Value has been spent
 *   isChange           BOOLEAN             This is a change transaction
 *   isCoinBase         BOOLEAN             This is a coinbase transaction
 *   inSafe             BOOLEAN             Transaction is in the safe
 *   isDeleted          BOOLEAN             Transaction is deleted
 * </pre>
 *
 * <p>The Sent table contains transactions sending coins from the wallet</p>
 * <pre>
 *   Column             Definition          Description
 *   ======             ==========          ===========
 *   normID             BYTEA               Normalized transaction ID
 *   txHash             BYTEA               Transaction hash
 *   txTime             BIGINT              Transaction timestamp
 *   blockHash          BYTEA               Chain block hash (zero hash if not confirmed yet)
 *   address            BYTEA               Send address
 *   value              BYTEA               Value
 *   fee                BYTEA               Fee
 *   isDeleted          BOOLEAN             Transaction is deleted
 *   txData             BYTEA               Serialized transaction
 * </pre>
 */
public class WalletPg extends Wallet {

    /** Addresses table definition */
    private static final String Addresses_Table = "CREATE TABLE Addresses ("+
            "address            BYTEA                   NOT NULL,"+
            "label              VARCHAR(64))";

    /** Keys table definition */
    private static final String Keys_Table = "CREATE TABLE Keys ("+
            "pubKey             BYTEA                   NOT NULL,"+
            "encPrivKey         BYTEA                   NOT NULL,"+
            "creationTime       BIGINT                  NOT NULL,"+
            "label              VARCHAR(64),"+
            "isChange           BOOLEAN                 NOT NULL)";

    /** Headers table definition */
    private static final String Headers_Table = "CREATE TABLE Headers ("+
            "blockHash          BYTEA                   NOT NULL PRIMARY KEY,"+
            "prevHash           BYTEA                   NOT NULL,"+
            "blockTime          BIGINT                  NOT NULL,"+
            "targetDifficulty   BIGINT                  NOT NULL,"+
            "merkleRoot         BYTEA                   NOT NULL,"+
            "onChain            BOOLEAN                 NOT NULL,"+
            "blockHeight        INTEGER                 NOT NULL,"+
            "chainWork          BYTEA                   NOT NULL,"+
            "matches            BYTEA)";
    private static final String Headers_IX1 = "CREATE INDEX Headers_IX1 ON Headers(blockHeight)";

    /** Received table definition */
    private static final String Received_Table = "CREATE TABLE Received ("+
            "normID             BYTEA                   NOT NULL,"+
            "txHash             BYTEA                   NOT NULL,"+
            "txIndex            INTEGER                 NOT NULL,"+
            "txTime             BIGINT                  NOT NULL,"+
            "blockHash          BYTEA                   NOT NULL,"+
            "address            BYTEA                   NOT NULL,"+
            "value              BYTEA                   NOT NULL,"+
            "scriptBytes        BYTEA                   NOT NULL,"+
            "isSpent            BOOLEAN                 NOT NULL,"+
            "isChange           BOOLEAN                 NOT NULL,"+
            "isCoinBase         BOOLEAN                 NOT NULL,"+
            "inSafe             BOOLEAN                 NOT NULL,"+
            "isDeleted          BOOLEAN                 NOT NULL)";
    private static final String Received_IX1 = "CREATE UNIQUE INDEX Received_IX1 ON Received(txHash,txIndex)";
    private static final String Received_IX2 = "CREATE INDEX Received_IX2 ON Received(normID)";

    /** Sent table definition */
    private static final String Sent_Table = "CREATE TABLE Sent ("+
            "normID             BYTEA                   NOT NULL,"+
            "txHash             BYTEA                   NOT NULL PRIMARY KEY,"+
            "txTime             BIGINT                  NOT NULL,"+
            "blockHash          BYTEA                   NOT NULL,"+
            "address            BYTEA                   NOT NULL,"+
            "value              BYTEA                   NOT NULL,"+
            "fee                BYTEA                   NOT NULL,"+
            "isDeleted          BOOLEAN                 NOT NULL,"+
            "txData             BYTEA                   NOT NULL)";
    private static final String Sent_IX1 = "CREATE INDEX Sent_IX1 ON Sent(normID)";

    /** Per-thread database connection */
    private ThreadLocal<Connection> threadConnection;

    /** List of all database connections */
    private List<Connection> allConnections;

    /** Database connection URL */
    private String connectionURL;

    /** Database connection user */
    private String connectionUser;

    /** Database connection password */
    private String connectionPassword;

    /**
     * Creates a Wallet using the PostgreSQL database
     *
     * @param       dbName              Database name
     * @throws      WalletException     Unable to initialize the database
     */
    public WalletPg(String dbName) throws WalletException {
        super();
        //
        // We will use the PostgreSQL database
        //
        connectionURL = "jdbc:postgresql://127.0.0.1:8335/"+dbName;
        connectionUser = "javabtc";
        connectionPassword = "btcnode";
        //
        // We will use a separate database connection for each thread
        //
        threadConnection = new ThreadLocal<>();
        allConnections = new ArrayList<>();
        //
        // Load the JDBC driver (Jaybird)
        //
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException exc) {
            log.error("Unable to load the PostgreSQL JDBC driver", exc);
            throw new WalletException("Unable to load the PostgreSQL JDBC driver");
        }
        //
        // Create the database tables if they don't exist
        //
        if (!tableExists("Headers")) {
            createTables();
        } else {
            getInitialValues();
        }
    }

    /**
     * Returns the chain height of the latest block earlier than the requested time.
     *
     * @param       rescanTime          Block chain rescan time
     * @return                          Block height or 0 if no block meets the criteria
     * @throws      WalletException     Unable to get the chain height
     */
    @Override
    public int getRescanHeight(long rescanTime) throws WalletException {
        int blockHeight = 0;
        Connection conn = checkConnection();
        ResultSet r;
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "SELECT MAX(blockHeight) FROM Headers WHERE blockTime<? AND onChain=true")) {
                s.setLong(1, rescanTime);
                r = s.executeQuery();
                if (r.next())
                    blockHeight = r.getInt(1);
                r.close();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to scan the block chain", exc));
            throw new WalletException("Unable to scan the block chain");
        }
        return blockHeight;
    }

    /**
     * Returns the block hash for the block at the requested height
     *
     * @param       blockHeight         Block height
     * @return                          Block Hash or null if block not found
     * @throws      WalletException     Unable to get block
     */
    @Override
    public Sha256Hash getBlockHash(int blockHeight) throws WalletException {
        Sha256Hash blockHash = null;
        Connection conn = checkConnection();
        ResultSet r;
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "SELECT blockHash FROM Headers WHERE blockHeight=? AND onChain=true")) {
                s.setInt(1, blockHeight);
                r = s.executeQuery();
                if (r.next())
                    blockHash = new Sha256Hash(r.getBytes(1));
                r.close();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to get block hash at height %d", blockHeight), exc);
            throw new WalletException("Unable to get block hash");
        }
        return blockHash;
    }

    /**
     * Returns the chain list from the block following the start block up to the stop
     * block.  A maximum of 500 blocks will be returned.
     *
     * @param       startHeight         Start block height
     * @param       stopBlock           Stop block
     * @return                          Block hash list
     * @throws      WalletException     Unable to get blocks from database
     */
    @Override
    public List<Sha256Hash> getChainList(int startHeight, Sha256Hash stopBlock) throws WalletException {
        List<Sha256Hash> chainList = new LinkedList<>();
        //
        // Get the chain list starting at the block following the start block and continuing
        // for a maximum of 500 blocks.
        //
        try {
            ResultSet r;
            Connection conn = checkConnection();
            try (PreparedStatement s = conn.prepareStatement(
                            "SELECT blockHash,blockHeight FROM Headers "+
                                        "WHERE onChain=true AND blockHeight>? AND blockHeight<=? "+
                                        "ORDER BY blockHeight ASC")) {
                s.setInt(1, startHeight);
                s.setInt(2, startHeight+500);
                r = s.executeQuery();
                while (r.next()) {
                    Sha256Hash blockHash = new Sha256Hash(r.getBytes(1));
                    chainList.add(blockHash);
                    if (blockHash.equals(stopBlock))
                        break;
                }
                r.close();
            }
        } catch (SQLException exc) {
            log.error("Unable to get the chain list", exc);
            throw new WalletException("Unable to get the chain list");
        }
        return chainList;
    }

    /**
     * Stores an address
     *
     * @param       address             Address
     * @throws      WalletException     Unable to store the address
     */
    @Override
    public void storeAddress(Address address) throws WalletException {
        Connection conn = checkConnection();
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "INSERT INTO Addresses (address,label) VALUES(?,?)")) {
                s.setBytes(1, address.getHash());
                String label = address.getLabel();
                if (label != null && label.length() != 0)
                    s.setString(2, label);
                else
                    s.setNull(2, Types.VARCHAR);
                s.executeUpdate();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to store address\n  %s", address.toString()), exc);
            throw new WalletException("Unable to store address");
        }
    }

    /**
     * Sets the address label
     *
     * @param       address             Address
     * @throws      WalletException     Unable to update label
     */
    @Override
    public void setAddressLabel(Address address) throws WalletException {
        Connection conn = checkConnection();
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "UPDATE Addresses SET label=? WHERE address=?")) {
                String label = address.getLabel();
                if (label != null && label.length() != 0)
                    s.setString(1, label);
                else
                    s.setNull(1, Types.VARCHAR);
                s.setBytes(2, address.getHash());
                s.executeUpdate();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to update address label\n  %s", address.toString()), exc);
            throw new WalletException("Unable to update address label");
        }
    }

    /**
     * Deletes an address
     *
     * @param       address             Address
     * @throws      WalletException     Unable to delete address
     */
    @Override
    public void deleteAddress(Address address) throws WalletException {
        Connection conn = checkConnection();
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "DELETE FROM Addresses WHERE address=?")) {
                s.setBytes(1, address.getHash());
                s.executeUpdate();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to delete address\n  %s", address.toString()), exc);
            throw new WalletException("Unable to delete address");
        }
    }

    /**
     * Returns a list of all addresses sorted by the label
     *
     * @return                          List of addresses stored in the database
     * @throws      WalletException     Unable to get address list
     */
    @Override
    public List<Address> getAddressList() throws WalletException {
        List<Address> addressList = new LinkedList<>();
        Connection conn = checkConnection();
        ResultSet r;
        try {
            try (Statement s = conn.createStatement()) {
                r = s.executeQuery("SELECT address,label FROM Addresses");
                while (r.next()) {
                    byte[] hashBytes = r.getBytes(1);
                    String label = r.getString(2);
                    Address addr = new Address(hashBytes, label!=null?label:"");
                    if (label == null) {
                        addressList.add(0, addr);
                    } else {
                        boolean added = false;
                        for (int i=0; i<addressList.size(); i++) {
                            if (addressList.get(i).getLabel().compareToIgnoreCase(label) > 0) {
                                addressList.add(i, addr);
                                added = true;
                                break;
                            }
                        }
                        if (!added)
                            addressList.add(addr);
                    }
                }
                r.close();
            }
        } catch (SQLException exc) {
            log.error("Unable to get address list", exc);
            throw new WalletException("Unable to get address list");
        }
        return addressList;
    }

    /**
     * Stores a key
     *
     * @param       key                 Public/private key pair
     * @throws      WalletException     Unable to store the key
     */
    @Override
    public void storeKey(ECKey key) throws WalletException {
        Connection conn = checkConnection();
        try {
            EncryptedPrivateKey encPrivKey = new EncryptedPrivateKey(key.getPrivKey(), Parameters.passPhrase);
            try (PreparedStatement s = conn.prepareStatement(
                            "INSERT INTO Keys (pubKey,encPrivKey,creationTime,label,isChange) "+
                                        "VALUES(?,?,?,?,?)")) {
                s.setBytes(1, key.getPubKey());
                s.setBytes(2, encPrivKey.getBytes());
                s.setLong(3, key.getCreationTime());
                String label = key.getLabel();
                if (label != null && label.length() != 0)
                    s.setString(4, label);
                else
                    s.setNull(4, Types.VARCHAR);
                s.setBoolean(5, key.isChange());
                s.executeUpdate();
            }
        } catch (ECException | SQLException exc) {
            log.error(String.format("Unable to store key\n  %s", key.toAddress().toString()), exc);
            throw new WalletException("Unable to store key");
        }
    }

    /**
     * Sets the key label
     *
     * @param       key                 Public/private key pair
     * @throws      WalletException     Unable to update the label
     */
    @Override
    public void setKeyLabel(ECKey key) throws WalletException {
        Connection conn = checkConnection();
        try {
            try (PreparedStatement s = conn.prepareStatement (
                    "UPDATE Keys SET label=? WHERE pubKey=?")) {
                String label = key.getLabel();
                if (label != null && label.length() != 0)
                    s.setString(1, label);
                else
                    s.setNull(1, Types.VARCHAR);
                s.setBytes(2, key.getPubKey());
                s.executeUpdate();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to update key label\n  %s", key.toAddress().toString()), exc);
            throw new WalletException("Unable to update key label");
        }
    }

    /**
     * Returns a list of all keys sorted by the label
     *
     * @return                          List of keys stored in the database
     * @throws      KeyException        Private key does not match public key
     * @throws      WalletException     Unable to get address list
     */
    @Override
    public List<ECKey> getKeyList() throws KeyException, WalletException {
        List<ECKey> keyList = new LinkedList<>();
        Connection conn = checkConnection();
        ResultSet r;
        EncryptedPrivateKey encPrivKey;
        try {
            try (Statement s = conn.createStatement()) {
                r = s.executeQuery("SELECT pubKey,encPrivKey,creationTime,label,isChange FROM Keys");
                while (r.next()) {
                    byte[] pubKey = r.getBytes(1);
                    encPrivKey = new EncryptedPrivateKey(r.getBytes(2));
                    long creationTime = r.getLong(3);
                    String label = r.getString(4);
                    boolean isChange = r.getBoolean(5);
                    BigInteger privKey = encPrivKey.getPrivKey(Parameters.passPhrase);
                    ECKey key = new ECKey(null, privKey, (pubKey.length==33));
                    if (!Arrays.equals(key.getPubKey(), pubKey))
                        throw new KeyException("Private key does not match public key");
                    key.setCreationTime(creationTime);
                    key.setLabel(label!=null?label:"");
                    key.setChange(isChange);
                    if (label == null) {
                        keyList.add(0, key);
                    } else {
                        boolean added = false;
                        for (int i=0; i<keyList.size(); i++) {
                            if (keyList.get(i).getLabel().compareToIgnoreCase(label) > 0) {
                                keyList.add(i, key);
                                added = true;
                                break;
                            }
                        }
                        if (!added)
                            keyList.add(key);
                    }
                }
                r.close();
            }
        } catch (ECException | EOFException | SQLException exc) {
            log.error("Unable to get key list", exc);
            throw new WalletException("Unable to get key list");
        }
        return keyList;
    }

    /**
     * Checks if this is a new block
     *
     * @param       blockHash           Block hash
     * @return                          TRUE if this is a new block
     * @throws      WalletException     Unable to check block status
     */
    @Override
    public boolean isNewBlock(Sha256Hash blockHash) throws WalletException {
        boolean isNewBlock = true;
        Connection conn = checkConnection();
        ResultSet r;
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "SELECT onChain FROM Headers WHERE blockHash=?")) {
                s.setBytes(1, blockHash.getBytes());
                r = s.executeQuery();
                if (r.next())
                    isNewBlock = false;
                r.close();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to check block status\n  %s", blockHash.toString()), exc);
            throw new WalletException("Unable to check block exceptoin", blockHash);
        }
        return isNewBlock;
    }

    /**
     * Stores a block header
     *
     * @param       header              Block header
     * @throws      WalletException     Unable to store the block header
     */
    @Override
    public void storeHeader(BlockHeader header) throws WalletException {
        Connection conn = checkConnection();
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "INSERT INTO Headers (blockHash,prevHash,blockTime,targetDifficulty,"+
                                        "merkleRoot,onChain,blockHeight,chainWork,matches) "+
                                        "VALUES(?,?,?,?,?,?,?,?,?)")) {
                s.setBytes(1, header.getHash().getBytes());
                s.setBytes(2, header.getPrevHash().getBytes());
                s.setLong(3, header.getBlockTime());
                s.setLong(4, header.getTargetDifficulty());
                s.setBytes(5, header.getMerkleRoot().getBytes());
                s.setBoolean(6,header.isOnChain());
                s.setInt(7, header.getBlockHeight());
                s.setBytes(8, header.getChainWork().toByteArray());
                List<Sha256Hash> matches = header.getMatches();
                if (matches != null && !matches.isEmpty())
                    s.setBytes(9, serializeHashList(matches));
                else
                    s.setNull(9, Types.BINARY);
                s.executeUpdate();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to store block header\n  %s", header.getHash().toString()), exc);
            throw new WalletException("Unable to store block header", header.getHash());
        }
    }

    /**
     * Updates the matched transactions for a block
     *
     * @param       header              Block Header
     * @throws      WalletException     Unable to update the database
     */
    @Override
    public void updateMatches(BlockHeader header) throws WalletException {
        Connection conn = checkConnection();
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "UPDATE Headers SET matches=? WHERE blockHash=?")) {
                List<Sha256Hash> matches = header.getMatches();
                if (matches != null && !matches.isEmpty())
                    s.setBytes(1, serializeHashList(matches));
                else
                    s.setNull(1, Types.BINARY);
                s.setBytes(2, header.getHash().getBytes());
                s.executeUpdate();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to update matched transactions\n  %s",
                                    header.getHash().toString()), exc);
            throw new WalletException("Unable to update matched transactions");
        }
    }

    /**
     * Returns a block header stored in the database
     *
     * @param       blockHash           Block hash
     * @return                          Block header or null if the block is not found
     * @throws      WalletException     Unable to retrieve the block header
     */
    @Override
    public BlockHeader getHeader(Sha256Hash blockHash) throws WalletException {
        BlockHeader header = null;
        Connection conn = checkConnection();
        ResultSet r;
        try {
            try (PreparedStatement s = conn.prepareStatement (
                            "SELECT prevHash,blockTime,targetDifficulty,merkleRoot,"+
                                            "onChain,blockHeight,chainWork,matches FROM Headers "+
                                            "WHERE blockHash=?")) {
                s.setBytes(1, blockHash.getBytes());
                r = s.executeQuery();
                if (r.next()) {
                    Sha256Hash prevHash = new Sha256Hash(r.getBytes(1));
                    long blockTime = r.getLong(2);
                    long targetDifficulty = r.getLong(3);
                    Sha256Hash merkleRoot = new Sha256Hash(r.getBytes(4));
                    boolean onChain = r.getBoolean(5);
                    int blockHeight = r.getInt(6);
                    BigInteger blockWork = new BigInteger(r.getBytes(7));
                    byte[] bytes = r.getBytes(8);
                    List<Sha256Hash> matches;
                    if (bytes != null)
                        matches = deserializeHashList(bytes);
                    else
                        matches = null;
                    header = new BlockHeader(blockHash, prevHash, blockTime, targetDifficulty,
                                             merkleRoot, onChain, blockHeight, blockWork, matches);
                }
                r.close();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to get block header\n  %s", blockHash.toString()), exc);
            throw new WalletException("Unable to get block header", blockHash);
        }
        return header;
    }

    /**
     * Returns the block header for the child of the specified block
     *
     * @param       parentHash          Parent block hash
     * @return                          Child block header or null if no child is found
     * @throws      WalletException     Unable to retrieve the child block header
     */
    @Override
    public BlockHeader getChildHeader(Sha256Hash parentHash) throws WalletException {
        BlockHeader childHeader = null;
        Connection conn = checkConnection();
        ResultSet r;
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "SELECT blockHash,blockTime,targetDifficulty,merkleRoot,onChain,"+
                                    "blockHeight,chainWork,matches FROM Headers WHERE prevHash=?")) {
                s.setBytes(1, parentHash.getBytes());
                r = s.executeQuery();
                if (r.next()) {
                    Sha256Hash blockHash = new Sha256Hash(r.getBytes(1));
                    long blockTime = r.getLong(2);
                    long targetDifficulty = r.getLong(3);
                    Sha256Hash merkleRoot = new Sha256Hash(r.getBytes(4));
                    boolean onChain = r.getBoolean(5);
                    int blockHeight = r.getInt(6);
                    BigInteger blockWork = new BigInteger(r.getBytes(7));
                    byte[] bytes = r.getBytes(8);
                    List<Sha256Hash> matches;
                    if (bytes != null)
                        matches = deserializeHashList(bytes);
                    else
                        matches = null;
                    childHeader = new BlockHeader(blockHash, parentHash, blockTime, targetDifficulty,
                                                  merkleRoot, onChain, blockHeight, blockWork, matches);
                }
                r.close();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to get child block header\n  %s", parentHash.toString()), exc);
            throw new WalletException("Unable to get child block header", parentHash);
        }
        return childHeader;
    }

    /**
     * Checks if this is a new transaction
     *
     * @param       txHash              Transaction hash
     * @return                          TRUE if this is a new transaction
     * @throws      WalletException     Unable to check transaction status
     */
    @Override
    public boolean isNewTransaction(Sha256Hash txHash) throws WalletException {
        boolean isNewTx = true;
        Connection conn = checkConnection();
        ResultSet r;
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "SELECT isDeleted from Received WHERE txHash=? "+
                                    "UNION SELECT isDeleted from Sent WHERE txHash=?")) {
                s.setBytes(1, txHash.getBytes());
                s.setBytes(2, txHash.getBytes());
                r = s.executeQuery();
                if (r.next())
                    isNewTx = false;
                r.close();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to check transaction status\n  %s", txHash.toString()), exc);
            throw new WalletException("Unable to check transaction status", txHash);
        }
        return isNewTx;
    }

    /**
     * Store a receive transaction
     *
     * @param       receiveTx           Receive transaction
     * @throws      WalletException     Unable to store the transaction
     */
    @Override
    public void storeReceiveTx(ReceiveTransaction receiveTx) throws WalletException {
        Connection conn = checkConnection();
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "INSERT INTO Received (normID,txHash,txIndex,txTime,blockHash,address,value,"+
                                            "scriptBytes,isSpent,isChange,isCoinBase,inSafe,isDeleted) "+
                                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                s.setBytes(1, receiveTx.getNormalizedID().getBytes());
                s.setBytes(2, receiveTx.getTxHash().getBytes());
                s.setInt(3, receiveTx.getTxIndex());
                s.setLong(4, receiveTx.getTxTime());
                s.setBytes(5, receiveTx.getBlockHash().getBytes());
                s.setBytes(6, receiveTx.getAddress().getHash());
                s.setBytes(7, receiveTx.getValue().toByteArray());
                s.setBytes(8, receiveTx.getScriptBytes());
                s.setBoolean(9, receiveTx.isSpent());
                s.setBoolean(10, receiveTx.isChange());
                s.setBoolean(11, receiveTx.isCoinBase());
                s.setBoolean(12, receiveTx.inSafe());
                s.setBoolean(13, receiveTx.isDeleted());
                s.executeUpdate();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to store receive transaction\n  %s",
                                    receiveTx.getTxHash().toString()), exc);
            throw new WalletException("Unable to store receive transaction", receiveTx.getTxHash());
        }
    }

    /**
     * Updates the spent status for a receive transaction
     *
     * @param       txHash              Transaction hash
     * @param       txIndex             Transaction output index
     * @param       isSpent             TRUE if the transaction output has been spent
     * @throws      WalletException     Unable to update transaction status
     */
    @Override
    public void setTxSpent(Sha256Hash txHash, int txIndex, boolean isSpent) throws WalletException {
        Connection conn = checkConnection();
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "UPDATE Received SET isSpent=? WHERE txHash=? AND txIndex=?")) {
                s.setBoolean(1, isSpent);
                s.setBytes(2, txHash.getBytes());
                s.setInt(3, txIndex);
                s.executeUpdate();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to update transaction status\n  %s", txHash.toString()), exc);
            throw new WalletException("Unable to update transaction status", txHash);
        }
    }

    /**
     * Updates the safe status for a receive transaction
     *
     * @param       txHash              Transaction hash
     * @param       txIndex             Transaction output index
     * @param       inSafe              TRUE if the transaction output is in the safe
     * @throws      WalletException     Unable to update transaction status
     */
    @Override
    public void setTxSafe(Sha256Hash txHash, int txIndex, boolean inSafe) throws WalletException {
        Connection conn = checkConnection();
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "UPDATE Received SET inSafe=? WHERE txHash=? AND txIndex=?")) {
                s.setBoolean(1, inSafe);
                s.setBytes(2, txHash.getBytes());
                s.setInt(3, txIndex);
                s.executeUpdate();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to update transaction status\n  %s", txHash.toString()), exc);
            throw new WalletException("Unable to update transaction status", txHash);
        }
    }

    /**
     * Updates the delete status for a receive transaction
     *
     * @param       txHash              Transaction hash
     * @param       txIndex             Transaction output index
     * @param       isDeleted           TRUE if the transaction output is deleted
     * @throws      WalletException     Unable to update transaction status
     */
    @Override
    public void setReceiveTxDelete(Sha256Hash txHash, int txIndex, boolean isDeleted) throws WalletException {
        Connection conn = checkConnection();
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "UPDATE Received SET isDeleted=? WHERE txHash=? AND txIndex=?")) {
                s.setBoolean(1, isDeleted);
                s.setBytes(2, txHash.getBytes());
                s.setInt(3, txIndex);
                s.executeUpdate();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to update transaction status\n  %s", txHash.toString()), exc);
            throw new WalletException("Unable to update transaction status", txHash);
        }
    }

    /**
     * Returns a list of all receive transactions that have not been deleted.  If we have multiple
     * transactions with the same normalized ID, we will return the one that has been confirmed.
     * If none of them are confirmed, we will return the first one we encounter.
     *
     * @return                          List of receive transactions
     * @throws      WalletException     Unable to get transaction list
     */
    @Override
    public List<ReceiveTransaction> getReceiveTxList() throws WalletException {
        List<ReceiveTransaction> txList = new LinkedList<>();
        Connection conn = checkConnection();
        try {
            ResultSet r;
            ReceiveTransaction prevTx = null;
            try (Statement s = conn.createStatement()) {
                r = s.executeQuery("SELECT normID,txHash,txIndex,txTime,blockHash,address,value,scriptBytes,"+
                                            "isSpent,isChange,isCoinBase,inSafe,isDeleted FROM Received "+
                                            "WHERE isDeleted=false ORDER BY normID ASC");
                while (r.next()) {
                    Sha256Hash normID = new Sha256Hash(r.getBytes(1));
                    Sha256Hash txHash = new Sha256Hash(r.getBytes(2));
                    int txIndex = r.getInt(3);
                    long txTime = r.getLong(4);
                    Sha256Hash blockHash = new Sha256Hash(r.getBytes(5));
                    Address address = new Address(r.getBytes(6));
                    BigInteger value = new BigInteger(r.getBytes(7));
                    byte[] scriptBytes = r.getBytes(8);
                    boolean isSpent = r.getBoolean(9);
                    boolean isChange = r.getBoolean(10);
                    boolean isCoinBase = r.getBoolean(11);
                    boolean inSafe = r.getBoolean(12);
                    boolean isDeleted = r.getBoolean(13);
                    if (isDeleted)
                        continue;
                    ReceiveTransaction tx = new ReceiveTransaction(normID, txHash, txIndex, txTime,
                                        blockHash, address, value, scriptBytes, isSpent, isChange,
                                        isCoinBase, inSafe, isDeleted);
                    if (prevTx != null && normID.equals(prevTx.getNormalizedID())) {
                        if (!blockHash.equals(Sha256Hash.ZERO_HASH)) {
                            txList.remove(txList.size()-1);
                            txList.add(tx);
                        }
                    } else {
                        txList.add(tx);
                    }
                    prevTx = tx;
                }
                r.close();
            }
        } catch (SQLException exc) {
            log.error("Unable to get receive transaction list", exc);
            throw new WalletException("Unable to get receive transaction list");
        }
        return txList;
    }

    /**
     * Store a send transaction
     *
     * @param       sendTx              Send transaction
     * @throws      WalletException     Unable to store the transaction
     */
    @Override
    public void storeSendTx(SendTransaction sendTx) throws WalletException {
        Connection conn = checkConnection();
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "INSERT INTO Sent (normID,txHash,txTime,blockHash,address,value,fee,"+
                                            "isDeleted,txData) VALUES(?,?,?,?,?,?,?,?,?)")) {
                s.setBytes(1, sendTx.getNormalizedID().getBytes());
                s.setBytes(2, sendTx.getTxHash().getBytes());
                s.setLong(3, sendTx.getTxTime());
                s.setBytes(4, sendTx.getBlockHash().getBytes());
                s.setBytes(5, sendTx.getAddress().getHash());
                s.setBytes(6, sendTx.getValue().toByteArray());
                s.setBytes(7, sendTx.getFee().toByteArray());
                s.setBoolean(8, sendTx.isDeleted());
                s.setBytes(9, sendTx.getTxData());
                s.executeUpdate();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to store send transaction\n  %s",
                                    sendTx.getTxHash().toString()), exc);
            throw new WalletException("Unable to store send transaction", sendTx.getTxHash());
        }
    }

    /**
     * Updates the delete status for a send transaction
     *
     * @param       txHash              Transaction hash
     * @param       isDeleted           TRUE if the transaction is deleted
     * @throws      WalletException     Unable to update transaction status
     */
    @Override
    public void setSendTxDelete(Sha256Hash txHash, boolean isDeleted) throws WalletException {
        Connection conn = checkConnection();
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "UPDATE Sent SET isDeleted=? WHERE txHash=?")) {
                s.setBoolean(1, isDeleted);
                s.setBytes(2, txHash.getBytes());
                s.executeUpdate();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to update transaction status\n  %s", txHash.toString()), exc);
            throw new WalletException("Unable to update transaction status", txHash);
        }
    }

    /**
     * Returns the requested send transaction
     *
     * @param       txHash              Send transaction hash
     * @return                          Transaction or null if not found
     * @throws      WalletException     Unable to get the transaction from the database
     */
    @Override
    public SendTransaction getSendTx(Sha256Hash txHash) throws WalletException {
        SendTransaction sendTx = null;
        Connection conn = checkConnection();
        ResultSet r;
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "SELECT normID,txTime,blockHash,address,value,fee,txData "+
                                    "FROM Sent WHERE txHash=? and isDeleted=false")) {
                s.setBytes(1, txHash.getBytes());
                r = s.executeQuery();
                if (r.next()) {
                    Sha256Hash normID = new Sha256Hash(r.getBytes(1));
                    long txTime = r.getLong(2);
                    Sha256Hash blockHash = new Sha256Hash(r.getBytes(3));
                    Address address = new Address(r.getBytes(4));
                    BigInteger value = new BigInteger(r.getBytes(5));
                    BigInteger fee = new BigInteger(r.getBytes(6));
                    byte[] txData = r.getBytes(7);
                    sendTx = new SendTransaction(normID, txHash, txTime, blockHash, address, value, fee,
                                                 false, txData);
                }
                r.close();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to get send transaction\n  %s", txHash.toString()), exc);
            throw new WalletException("Unable to get send transaction");
        }
        return sendTx;
    }

    /**
     * Returns a list of all send transactions that have not been deleted.  If we have multiple
     * transactions with the same normalized ID, we will return the one that has been confirmed.
     * If none of them are confirmed, we will return the first one we encounter.
     *
     * @return                          List of send transactions
     * @throws      WalletException     Unable to get transaction list
     */
    @Override
    public List<SendTransaction> getSendTxList() throws WalletException {
        List<SendTransaction> txList = new LinkedList<>();
        Connection conn = checkConnection();
        try {
            ResultSet r;
            SendTransaction prevTx = null;
            try (Statement s = conn.createStatement()) {
                r = s.executeQuery("SELECT normID,txHash,txTime,blockHash,address,value,fee,"+
                                "isDeleted,txData FROM Sent WHERE isDeleted=false");
                while (r.next()) {
                    Sha256Hash normID = new Sha256Hash(r.getBytes(1));
                    Sha256Hash txHash = new Sha256Hash(r.getBytes(2));
                    long txTime = r.getLong(3);
                    Sha256Hash blockHash = new Sha256Hash(r.getBytes(4));
                    Address address = new Address(r.getBytes(5));
                    BigInteger value = new BigInteger(r.getBytes(6));
                    BigInteger fee = new BigInteger(r.getBytes(7));
                    boolean isDeleted = r.getBoolean(8);
                    byte[] txData = r.getBytes(9);
                    if (isDeleted)
                        continue;
                    SendTransaction tx = new SendTransaction(normID, txHash, txTime, blockHash,
                                            address, value, fee, isDeleted, txData);
                    if (prevTx != null && normID.equals(prevTx.getNormalizedID())) {
                        if (!blockHash.equals(Sha256Hash.ZERO_HASH)) {
                            txList.remove(txList.size()-1);
                            txList.add(tx);
                        }
                    } else {
                        txList.add(tx);
                    }
                    prevTx = tx;
                }
                r.close();
            }
        } catch (SQLException exc) {
            log.error("Unable to get send transaction list", exc);
            throw new WalletException("Unable to get send transaction list");
        }
        return txList;
    }

    /**
     * Returns the transaction depth.  This is the number of blocks in the chain
     * including the block containing the transaction.  So a depth of 0 indicates
     * the transaction has not been confirmed, a depth of 1 indicates just the
     * block containing the transaction is on the chain, etc.
     *
     * @param       txHash                  Transaction hash
     * @return                              Confirmation depth
     * @throws      WalletException         Unable to get transaction depth
     */
    @Override
    public int getTxDepth(Sha256Hash txHash) throws WalletException {
        int txDepth = 0;
        Connection conn = checkConnection();
        ResultSet r;
        try {
            try (PreparedStatement s = conn.prepareStatement(
                            "SELECT Headers.blockHeight FROM Headers INNER JOIN Received "+
                                    "ON Received.blockHash=Headers.blockHash WHERE Received.txHash=? "+
                                    "UNION SELECT Headers.blockHeight FROM Headers INNER JOIN Sent "+
                                    "ON Sent.blockHash=Headers.blockHash WHERE Sent.txHash=?")) {
                s.setBytes(1, txHash.getBytes());
                s.setBytes(2, txHash.getBytes());
                r = s.executeQuery();
                if (r.next()) {
                    int blockHeight = r.getInt(1);
                    txDepth = chainHeight - blockHeight + 1;
                }
                r.close();
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to get transaction depth\n  %s", txHash.toString()), exc);
            throw new WalletException("Unable to get transaction depth", txHash);
        }
        return txDepth;
    }

    /**
     * Locates the junction where the chain represented by the specified block joins
     * the current block chain.  The returned list starts with the junction block
     * and contains all blocks in the chain leading to the specified block.
     *
     * A BlockNotFoundException will be thrown if the chain cannot be resolved because a
     * block is missing.  The caller should get the block from a peer, store it in the
     * database and then retry.
     *
     * @param       chainHash               The block hash of the chain head
     * @return                              List of blocks in the chain leading to the new head
     * @throws      BlockNotFoundException  A block in the chain was not found
     * @throws      WalletException         Unable to get blocks from the database
     */
    @Override
    public List<BlockHeader> getJunction(Sha256Hash chainHash)
                         throws BlockNotFoundException, WalletException {
        List<BlockHeader> chainList = new LinkedList<>();
        boolean onChain = false;
        Sha256Hash blockHash = chainHash;
        BlockHeader header;
        long targetDifficulty;
        long blockTime;
        Sha256Hash merkleRoot;
        Sha256Hash prevHash;
        int blockHeight;
        BigInteger blockWork;
        List<Sha256Hash> matches;
        Connection conn = checkConnection();
        ResultSet r;
        synchronized (lock) {
            //
            // Starting with the supplied block, follow the previous hash values until
            // we reach a block which is on the block chain.  This block is the junction
            // block.
            //
            try {
                PreparedStatement s1 = conn.prepareStatement(
                                "SELECT prevhash,onChain,blockHeight,chainWork,targetDifficulty,merkleRoot,"+
                                                "blockTime,matches FROM Headers WHERE blockHash=?");
                while (!onChain) {
                    s1.setBytes(1, blockHash.getBytes());
                    r = s1.executeQuery();
                    if (r.next()) {
                        prevHash = new Sha256Hash(r.getBytes(1));
                        onChain = r.getBoolean(2);
                        blockHeight = r.getInt(3);
                        blockWork = new BigInteger(r.getBytes(4));
                        targetDifficulty = r.getLong(5);
                        merkleRoot = new Sha256Hash(r.getBytes(6));
                        blockTime = r.getLong(7);
                        byte[] bytes = r.getBytes(8);
                        if (bytes != null)
                            matches = deserializeHashList(bytes);
                        else
                            matches = null;
                        r.close();
                        header = new BlockHeader(blockHash, prevHash, blockTime, targetDifficulty, merkleRoot,
                                                 onChain, blockHeight, blockWork, matches);
                        chainList.add(0, header);
                        blockHash = prevHash;
                    } else {
                        r.close();
                        log.warn(String.format("Chain block is not available\n  %s", blockHash.toString()));
                        throw new BlockNotFoundException("Unable to resolve block chain", blockHash);
                    }
                }
            } catch (SQLException exc) {
                log.error("Unable to locate junction block", exc);
                throw new WalletException("Unable to locate junction block", blockHash);
            }
        }
        return chainList;
    }

    /**
     * Changes the chain head and updates all blocks from the junction block up to the new
     * chain head.  The junction block is the point where the current chain and the new
     * chain intersect.  A VerificationException will be thrown if the new chain head is
     * for a checkpoint block and the block hash doesn't match the checkpoint hash.
     *
     * @param       chainList               List of all chain blocks starting with the junction block
     *                                      up to and including the new chain head
     * @throws      VerificationException   Chain verification failed
     * @throws      WalletException         Unable to update the database
     */
    @Override
    public void setChainHead(List<BlockHeader> chainList) throws WalletException, VerificationException {
        //
        // See if we have reached a checkpoint.  If we have, the new block at that height
        // must match the checkpoint block.
        //
        for (BlockHeader header : chainList) {
            Sha256Hash checkHash = checkpoints.get(Integer.valueOf(header.getBlockHeight()));
            if (checkHash != null) {
                if (checkHash.equals(header.getHash())) {
                    log.info(String.format("New chain head at height %d matches checkpoint",
                                           header.getBlockHeight()));
                } else {
                    log.error(String.format("New chain head at height %d does not match checkpoint",
                                            header.getBlockHeight()));
                    throw new VerificationException("Checkpoint verification failed",
                                                    Parameters.REJECT_CHECKPOINT, header.getHash());
                }
            }
        }
        BlockHeader chainHeader = chainList.get(chainList.size()-1);
        Connection conn = checkConnection();
        ResultSet r;
        //
        // Make the new block the chain head
        //
        synchronized (lock) {
            Sha256Hash blockHash;
            Sha256Hash prevHash;
            BlockHeader header;
            List<Sha256Hash> txList;
            PreparedStatement s1 = null;
            PreparedStatement s2 = null;
            PreparedStatement s3 = null;
            PreparedStatement s4 = null;
            try {
                conn.setAutoCommit(false);
                s1 = conn.prepareStatement("UPDATE Received SET blockHash=? WHERE txHash=?");
                s2 = conn.prepareStatement("UPDATE Sent SET blockHash=? WHERE txHash=?");
                s3 = conn.prepareStatement("UPDATE Headers SET onChain=?,blockHeight=?,chainWork=? "+
                                           "WHERE blockHash=?");
                s4 = conn.prepareStatement("SELECT prevHash,matches FROM Headers WHERE blockHash=?");
                //
                // The ideal case is where the new block links to the current chain head.
                // If this is not the case, we need to remove all blocks from the block
                // chain following the junction block.
                //
                if (!chainHead.equals(chainHeader.getPrevHash())) {
                    Sha256Hash junctionHash = chainList.get(0).getHash();
                    blockHash = chainHead;
                    //
                    // Process each block starting at the current chain head and working backwards
                    // until we reach the junction block
                    //
                    while(!blockHash.equals(junctionHash)) {
                        //
                        // Get the previous block hash and matched transactions
                        //
                        s4.setBytes(1, blockHash.getBytes());
                        r = s4.executeQuery();
                        if (!r.next()) {
                            r.close();
                            log.error(String.format("Chain block not found\n  %s", blockHash.toString()));
                            throw new WalletException("Chain block not found", blockHash);
                        }
                        prevHash = new Sha256Hash(r.getBytes(1));
                        byte[] bytes = r.getBytes(2);
                        r.close();
                        //
                        // Update the matched transactions to indicate they are no longer confirmed
                        //
                        if (bytes != null) {
                            txList = deserializeHashList(bytes);
                            for (Sha256Hash txHash : txList) {
                                s1.setBytes(1, Sha256Hash.ZERO_HASH.getBytes());
                                s1.setBytes(2, txHash.getBytes());
                                s1.executeUpdate();
                                s2.setBytes(1, Sha256Hash.ZERO_HASH.getBytes());
                                s2.setBytes(2, txHash.getBytes());
                                s2.executeUpdate();
                            }
                        }
                        //
                        // Remove the block from the chain
                        //
                        s3.setBoolean(1, false);
                        s3.setInt(2, 0);
                        s3.setBytes(3, BigInteger.ZERO.toByteArray());
                        s3.setBytes(4, blockHash.getBytes());
                        s3.executeUpdate();
                        log.info(String.format("Block removed from block chain\n  %s", blockHash.toString()));
                        //
                        // Advance to the block before this block
                        //
                        blockHash = prevHash;
                    }
                }
                //
                // Now add the new blocks to the block chain starting with the
                // block following the junction block
                //
                for (int i=1; i<chainList.size(); i++) {
                    header = chainList.get(i);
                    blockHash = header.getHash();
                    int blockHeight = header.getBlockHeight();
                    BigInteger blockWork = header.getChainWork();
                    txList = header.getMatches();
                    //
                    // Update the sent and receive transactions for this block to indicate
                    // they are now confirmed
                    //
                    if (txList != null) {
                        for (Sha256Hash txHash : txList) {
                            s1.setBytes(1, blockHash.getBytes());
                            s1.setBytes(2, txHash.getBytes());
                            s1.executeUpdate();
                            s2.setBytes(1, blockHash.getBytes());
                            s2.setBytes(2, txHash.getBytes());
                            s2.executeUpdate();
                        }
                    }
                    //
                    // Update the block status
                    //
                    s3.setBoolean(1, true);
                    s3.setInt(2, blockHeight);
                    s3.setBytes(3, blockWork.toByteArray());
                    s3.setBytes(4, blockHash.getBytes());
                    s3.executeUpdate();
                    log.info(String.format("Block added to block chain at height %d\n  %s",
                                           blockHeight, blockHash.toString()));
                }
                s1.close();
                s1 = null;
                s2.close();
                s2 = null;
                s3.close();
                s3 = null;
                s4.close();
                s4 = null;
                //
                // Commit the changes
                //
                conn.commit();
                conn.setAutoCommit(true);
                //
                // Update chain head values for the block we just added
                //
                chainHead = chainHeader.getHash();
                chainHeight = chainHeader.getBlockHeight();
                chainWork = chainHeader.getChainWork();
            } catch (SQLException exc) {
                log.error("Unable to update block chain", exc);
                rollback(s1, s2, s3, s4);
                throw new WalletException("Unable to update block chain");
            }
        }
    }

    /**
     * Closes the database
     */
    @Override
    public void close() {
        //
        // Close all database connections
        //
        for (Connection c : allConnections) {
            try {
                c.close();
            } catch (SQLException exc) {
                log.error("SQL error while closing connections", exc);
            }
        }
        allConnections.clear();
    }

    /**
     * Checks the database connection for the current thread and gets a
     * new connection if necessary
     *
     * @return      Connection for the current thread
     * @throws      WalletException     Unable to obtain a database connection
     */
    private Connection checkConnection() throws WalletException {
        //
        // Nothing to do if we already have a connection for this thread
        //
        Connection conn = threadConnection.get();
        if (conn != null)
            return conn;
        //
        // Set up a new connection
        //
        synchronized (lock) {
            try {
                threadConnection.set(
                        DriverManager.getConnection(connectionURL, connectionUser, connectionPassword));
                conn = threadConnection.get();
                allConnections.add(conn);
                log.info(String.format("New connection created to SQL database %s", connectionURL));
            } catch (SQLException exc) {
                log.error(String.format("Unable to connect to SQL database %s", connectionURL), exc);
                throw new WalletException("Unable to connect to SQL database");
            }
        }
        return conn;
    }

    /**
     * Rollback the current transaction and turn auto commit back on
     *
     * @param       stmt            Statement to be closed or null
     */
    private void rollback(AutoCloseable... stmts) {
        try {
            Connection conn = checkConnection();
            for (AutoCloseable stmt : stmts)
                if (stmt != null)
                    stmt.close();
            conn.rollback();
            conn.setAutoCommit(true);
        } catch (Exception exc) {
            log.error("Unable to rollback transaction", exc);
        }
    }

    /**
     * Checks if a table exists
     *
     * @param       table               Table name
     * @return                          TRUE if the table exists
     * @throws      WalletException     Unable to access the database server
     */
    private boolean tableExists(String table) throws WalletException {
        boolean tableExists;
        Connection conn = checkConnection();
        try {
            try (Statement s = conn.createStatement()) {
                s.executeQuery("SELECT * FROM "+table+" WHERE 1 = 2");
                tableExists = true;
            }
        } catch (SQLException exc) {
            tableExists = false;
        }
        return tableExists;
    }

    /**
     * Create the tables
     *
     * @throws      WalletException     Unable to create database tables
     */
    private void createTables() throws WalletException {
        Connection conn = checkConnection();
        try {
            conn.setAutoCommit(false);
            //
            // Create tables and indexes
            //
            try (Statement s = conn.createStatement()) {
                s.executeUpdate(Addresses_Table);
                s.executeUpdate(Keys_Table);
                s.executeUpdate(Headers_Table);
                s.executeUpdate(Headers_IX1);
                s.executeUpdate(Received_Table);
                s.executeUpdate(Received_IX1);
                s.executeUpdate(Received_IX2);
                s.executeUpdate(Sent_Table);
                s.executeUpdate(Sent_IX1);
            }
            //
            // Add the genesis block to the Headeres table
            //
            chainHead = new Sha256Hash(Parameters.GENESIS_BLOCK_HASH);
            chainHeight = 0;
            chainWork = BigInteger.valueOf(1);
            try (PreparedStatement s = conn.prepareStatement(
                            "INSERT INTO Headers (blockHash,prevHash,blockTime,targetDifficulty,merkleRoot,"+
                                        "matches,onChain,blockHeight,chainWork)"+
                                        "VALUES(?,?,?,?,?,?,true,0,'\\x01')")) {
                s.setBytes(1, chainHead.getBytes());
                s.setBytes(2, Sha256Hash.ZERO_HASH.getBytes());
                s.setLong(3, Parameters.GENESIS_BLOCK_TIME);
                s.setLong(4, Parameters.MAX_TARGET_DIFFICULTY);
                s.setBytes(5, Sha256Hash.ZERO_HASH.getBytes());
                s.setNull(6, Types.BINARY);
                s.executeUpdate();
            }
            //
            // Commit the updates
            //
            conn.commit();
            conn.setAutoCommit(true);
            log.info("SQL database tables created");
        } catch (SQLException exc) {
            log.error("Unable to create SQL database tables", exc);
            rollback();
            throw new WalletException("Unable to create SQL database tables");
        }
    }

    /**
     * Get the initial values
     *
     * @throws      WalletException     Unable to get initial values from database
     */
    private void getInitialValues() throws WalletException {
        Connection conn = checkConnection();
        try {
            ResultSet r;
            //
            // Get the chain height from the Headers table
            //
            try (Statement s = conn.createStatement()) {
                r = s.executeQuery("SELECT MAX(blockHeight) FROM Headers WHERE onChain=true");
                if (!r.next()) {
                    r.close();
                    throw new WalletException("Database tables are not initialized");
                }
                chainHeight = r.getInt(1);
                r.close();
            }
            //
            // Get the chain head from the Headers table
            //
            try (PreparedStatement s = conn.prepareStatement(
                            "SELECT blockHash,chainWork FROM Headers WHERE blockHeight=? AND onChain=true")) {
                s.setInt(1, chainHeight);
                r = s.executeQuery();
                if (r.next()) {
                    chainHead = new Sha256Hash(r.getBytes(1));
                    chainWork = new BigInteger(r.getBytes(2));
                    r.close();
                } else {
                    r.close();
                    throw new WalletException("Database tables are not initialized");
                }
            }
            log.info(String.format("Chain height %,d\nChainhead %s", chainHeight, chainHead));
        } catch (SQLException exc) {
            log.error("Unable to get initial values from database tables", exc);
            throw new WalletException("Unable to get initial values");
        }
    }

    /**
     * Serialize a Sha256Hash list
     *
     * @param       hashList            List of hashes
     * @return                          Serialize byte data
     */
    private byte[] serializeHashList(List<Sha256Hash> hashList) {
        byte[] bytes = new byte[hashList.size()*32];
        int offset = 0;
        for (Sha256Hash hash : hashList) {
            System.arraycopy(hash.getBytes(), 0, bytes, offset, 32);
            offset += 32;
        }
        return bytes;
    }

    /**
     * Deserialize a Sha256Hash list
     *
     * @param       hashBytes           Serialized data
     * @return                          Hash listt
     */
    private List<Sha256Hash> deserializeHashList(byte[] bytes) {
        List<Sha256Hash> hashList = new ArrayList<>(bytes.length/32);
        for (int offset=0; offset<bytes.length; offset+=32)
            hashList.add(new Sha256Hash(bytes, offset, 32));
        return hashList;
    }
}
