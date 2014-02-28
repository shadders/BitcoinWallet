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

import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;

import org.fusesource.leveldbjni.JniDBFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.File;

import java.math.BigInteger;

import java.nio.charset.Charset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * <p>A Wallet stores block headers, transactions, addresses and keys.  These are used to
 * access bitcoins recorded in the block chain.  A wallet can be deleted and recreated as long
 * as the private keys have been exported and then imported into the new wallet.</p>
 */
public class WalletLdb extends Wallet {

    /** UTF-8 character set */
    private final Charset charset = Charset.forName("UTF-8");

    /** Headers database */
    private DB dbHeaders;

    /** BlockChain database */
    private DB dbBlockChain;

    /** Child database */
    private DB dbChild;

    /** Received database */
    private DB dbReceived;

    /** Sent database */
    private DB dbSent;

    /** Address database */
    private DB dbAddress;

    /** Keys database */
    private DB dbKeys;

    /** Application data path */
    private String dataPath;

    /**
     * Creates a Wallet using the LevelDB database
     *
     * @param       dataPath            Application data path
     * @throws      WalletException     Unable to initialize the database
     */
    public WalletLdb(String dataPath) throws WalletException {
        super();
        this.dataPath = dataPath;
        Options options = new Options();
        options.createIfMissing(true);
        options.compressionType(CompressionType.NONE);
        options.maxOpenFiles(32);
        log.info(String.format("LevelDB version %s", JniDBFactory.VERSION));
        //
        // Create the LevelDB base directory
        //
        File databaseDir = new File(dataPath+"\\LevelDB");
        if (!databaseDir.exists())
            databaseDir.mkdirs();
        try {
            Entry<byte[], byte[]> dbEntry;
            byte[] entryData;
            //
            // Open the BlockChain database
            //
            File fileBlockChain = new File(dataPath+"\\LevelDB\\BlockChainDB");
            dbBlockChain = JniDBFactory.factory.open(fileBlockChain, options);
            //
            // Open the Headers database
            //
            File fileBlocks = new File(dataPath+"\\LevelDB\\HeadersDB");
            dbHeaders = JniDBFactory.factory.open(fileBlocks, options);
            //
            // Open the Child database
            //
            File fileChild = new File(dataPath+"\\LevelDB\\ChildDB");
            dbChild = JniDBFactory.factory.open(fileChild, options);
            //
            // Open the Received database
            //
            File fileReceived = new File(dataPath+"\\LevelDB\\ReceivedDB");
            dbReceived = JniDBFactory.factory.open(fileReceived, options);
            //
            // Open the Sent database
            //
            File fileSent = new File(dataPath+"\\LevelDB\\SentDB");
            dbSent = JniDBFactory.factory.open(fileSent, options);
            //
            // Open the Address database
            //
            File fileAddress = new File(dataPath+"\\LevelDB\\AddressDB");
            dbAddress = JniDBFactory.factory.open(fileAddress, options);
            //
            // Open the Keys database
            //
            File fileKeys = new File(dataPath+"\\LevelDB\\KeysDB");
            dbKeys = JniDBFactory.factory.open(fileKeys, options);
            //
            // Get the initial values from the database
            //
            try (DBIterator it = dbBlockChain.iterator()) {
                it.seekToLast();
                if (it.hasNext()) {
                    dbEntry = it.next();
                    //
                    // Get the current chain head from the BlockChain database
                    //
                    chainHeight = getInteger(dbEntry.getKey());
                    chainHead = new Sha256Hash(dbEntry.getValue());
                    //
                    // Get the chain head block from the Headers database
                    //
                    entryData = dbHeaders.get(chainHead.getBytes());
                    if (entryData == null) {
                        log.error(String.format("Chain head block not found in Headers database\n  %s",
                                                chainHead.toString()));
                        throw new WalletException("Chain head block not found in Headers database", chainHead);
                    }
                    BlockEntry blockEntry = new BlockEntry(entryData);
                    chainWork = blockEntry.getChainWork();
                    //
                    // Initialization complete
                    //
                    log.info(String.format("Database initialized\n  Chain height %d\n  Chain head %s",
                                           chainHeight, chainHead.toString()));
                } else {
                    //
                    // We are creating a new database
                    //
                    chainHead = new Sha256Hash(Parameters.GENESIS_BLOCK_HASH);
                    chainHeight = 0;
                    chainWork = BigInteger.valueOf(1);
                    //
                    // Add the genesis block to the block chain
                    //
                    BlockHeader header = new BlockHeader(Parameters.GENESIS_BLOCK_BYTES);
                    BlockEntry blockEntry = new BlockEntry(Sha256Hash.ZERO_HASH, header.getBlockTime(),
                                                           header.getTargetDifficulty(), header.getMerkleRoot(),
                                                           true, chainHeight, chainWork, null);
                    dbHeaders.put(chainHead.getBytes(), blockEntry.getBytes());
                    dbBlockChain.put(getIntegerBytes(chainHeight), chainHead.getBytes());
                    //
                    // Databases created
                    //
                    log.info("LevelDB databases created");
                }
            }
        } catch (DBException | IOException | VerificationException exc) {
            log.error("Unable to initialize wallet", exc);
            throw new WalletException("Unable to initialize wallet");
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
        long earliestTime = 0;
        synchronized(lock) {
            try {
                try (DBIterator it = dbHeaders.iterator()) {
                    it.seekToFirst();
                    while (it.hasNext()) {
                        Entry<byte[], byte[]> dbEntry = it.next();
                        BlockEntry blockEntry = new BlockEntry(dbEntry.getValue());
                        if (blockEntry.isOnChain()) {
                            long blockTime = blockEntry.getTimeStamp();
                            if (blockTime > earliestTime && blockTime < rescanTime) {
                                earliestTime = blockTime;
                                blockHeight = blockEntry.getHeight();
                            }
                        }
                    }
                }
            } catch (DBException | IOException exc) {
                log.error(String.format("Unable to scan the block chain", exc));
                throw new WalletException("Unable to scan the block chain");
            }
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
        try {
            byte[] entryData = dbBlockChain.get(getIntegerBytes(blockHeight));
            if (entryData != null)
                blockHash = new Sha256Hash(entryData);
        } catch (DBException exc) {
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
        synchronized(lock) {
            try {
                try (DBIterator it = dbBlockChain.iterator()) {
                    it.seek(getIntegerBytes(startHeight+1));
                    while (it.hasNext()) {
                        Entry<byte[], byte[]> dbEntry = it.next();
                        Sha256Hash blockHash = new Sha256Hash(dbEntry.getValue());
                        chainList.add(blockHash);
                        if (chainList.size() >= 500 || blockHash.equals(stopBlock))
                            break;
                    }
                }
            } catch (DBException | IOException exc) {
                log.error("Unable to get the chain list", exc);
                throw new WalletException("Unable to get the chain list");
            }
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
        try {
            String label = address.getLabel();
            byte[] labelBytes;
            if (label.length() == 0)
                labelBytes = new byte[1];
            else
                labelBytes = label.getBytes(charset);
            dbAddress.put(address.getHash(), labelBytes);
        } catch (DBException exc) {
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
        try {
            String label = address.getLabel();
            byte[] labelBytes;
            if (label.length() == 0)
                labelBytes = new byte[1];
            else
                labelBytes = label.getBytes(charset);
            dbAddress.put(address.getHash(), labelBytes);
        } catch (DBException exc) {
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
        try {
            dbAddress.delete(address.getHash());
        } catch (DBException exc) {
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
        try {
            try (DBIterator it = dbAddress.iterator()) {
                it.seekToFirst();
                while (it.hasNext()) {
                    Entry<byte[], byte[]> dbEntry = it.next();
                    byte[] addressBytes = dbEntry.getKey();
                    byte[] labelBytes = dbEntry.getValue();
                    String label;
                    if (labelBytes.length == 1 && labelBytes[0] == 0)
                        label = "";
                    else
                        label = new String(labelBytes, charset);
                    Address addr = new Address(addressBytes, label);
                    if (label.length() == 0) {
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
            }
        } catch (DBException | IOException exc) {
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
        try {
            EncryptedPrivateKey encPrivKey = new EncryptedPrivateKey(key.getPrivKey(),
                                                                     Parameters.passPhrase);
            KeyEntry keyEntry = new KeyEntry(encPrivKey.getBytes(), key.getCreationTime(),
                                             key.getLabel(), key.isChange());
            dbKeys.put(key.getPubKey(), keyEntry.getBytes());
        } catch (ECException | DBException exc) {
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
        try {
            byte[] entryData = dbKeys.get(key.getPubKey());
            if (entryData != null) {
                KeyEntry keyEntry = new KeyEntry(entryData);
                keyEntry.setLabel(key.getLabel());
                dbKeys.put(key.getPubKey(), keyEntry.getBytes());
            }
        } catch (DBException | EOFException exc) {
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
        EncryptedPrivateKey encPrivKey;
        try {
            try (DBIterator it = dbKeys.iterator()) {
                it.seekToFirst();
                while (it.hasNext()) {
                    Entry<byte[], byte[]> dbEntry = it.next();
                    byte[] pubKey = dbEntry.getKey();
                    KeyEntry keyEntry = new KeyEntry(dbEntry.getValue());
                    encPrivKey = new EncryptedPrivateKey(keyEntry.getPrivKey());
                    long creationTime = keyEntry.getCreationTime();
                    String label = keyEntry.getLabel();
                    boolean isChange = keyEntry.isChange();
                    BigInteger privKey = encPrivKey.getPrivKey(Parameters.passPhrase);
                    ECKey key = new ECKey(null, privKey, (pubKey.length==33));
                    if (!Arrays.equals(key.getPubKey(), pubKey))
                        throw new KeyException("Private key does not match public key");
                    key.setCreationTime(creationTime);
                    key.setLabel(label);
                    key.setChange(isChange);
                    if (label.length() == 0) {
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
            }
        } catch (DBException | ECException | IOException exc) {
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
        try {
            if (dbHeaders.get(blockHash.getBytes()) != null)
                isNewBlock = false;
        } catch (DBException exc) {
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
        try {
            BlockEntry blockEntry = new BlockEntry(header.getPrevHash(), header.getBlockTime(),
                                            header.getTargetDifficulty(), header.getMerkleRoot(),
                                            header.isOnChain(), header.getBlockHeight(),
                                            header.getChainWork(), header.getMatches());
            dbHeaders.put(header.getHash().getBytes(), blockEntry.getBytes());
            dbChild.put(header.getPrevHash().getBytes(), header.getHash().getBytes());
        } catch (DBException exc) {
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
        try {
            byte[] entryData = dbHeaders.get(header.getHash().getBytes());
            if (entryData != null) {
                BlockEntry blockEntry = new BlockEntry(entryData);
                blockEntry.setMatches(header.getMatches());
                dbHeaders.put(header.getHash().getBytes(), blockEntry.getBytes());
            }
        } catch (DBException | EOFException exc) {
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
        try {
            byte[] entryData = dbHeaders.get(blockHash.getBytes());
            if (entryData != null) {
                BlockEntry blockEntry = new BlockEntry(entryData);
                header = new BlockHeader(blockHash, blockEntry.getPrevHash(),
                                        blockEntry.getTimeStamp(), blockEntry.getTargetDifficulty(),
                                        blockEntry.getMerkleRoot(), blockEntry.isOnChain(),
                                        blockEntry.getHeight(), blockEntry.getChainWork(),
                                        blockEntry.getMatches());
            }
        } catch (DBException | EOFException exc) {
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
        try {
            byte[] entryData = dbChild.get(parentHash.getBytes());
            if (entryData != null) {
                Sha256Hash childHash = new Sha256Hash(entryData);
                entryData = dbHeaders.get(childHash.getBytes());
                if (entryData != null) {
                    BlockEntry blockEntry = new BlockEntry(entryData);
                    childHeader = new BlockHeader(childHash, blockEntry.getPrevHash(),
                                        blockEntry.getTimeStamp(), blockEntry.getTargetDifficulty(),
                                        blockEntry.getMerkleRoot(), blockEntry.isOnChain(),
                                        blockEntry.getHeight(), blockEntry.getChainWork(),
                                        blockEntry.getMatches());
                }
            }
        } catch (DBException | EOFException exc) {
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
        try {
            if (dbSent.get(txHash.getBytes()) != null) {
                isNewTx = false;
            } else {
                try (DBIterator it = dbReceived.iterator()) {
                    it.seek(txHash.getBytes());
                    if (it.hasNext()) {
                        Entry<byte[], byte[]> dbEntry = it.next();
                        TransactionID txID = new TransactionID(dbEntry.getKey());
                        if (txID.getTxHash().equals(txHash))
                            isNewTx = false;
                    }
                }
            }
        } catch (DBException | IOException exc) {
            log.error(String.format("Unable to check transaction status\n  %s", txHash.toString()), exc);
            throw new WalletException("Unable to check transaction status", txHash);
        }
        return isNewTx;
    }

    /**
     * Store a receive transaction
     *
     * @param       rcvTx               Receive transaction
     * @throws      WalletException     Unable to store the transaction
     */
    @Override
    public void storeReceiveTx(ReceiveTransaction rcvTx) throws WalletException {
        try {
            TransactionID txID = new TransactionID(rcvTx.getTxHash(), rcvTx.getTxIndex());
            ReceiveEntry rcvEntry = new ReceiveEntry(rcvTx.getNormalizedID(), rcvTx.getTxTime(),
                                            rcvTx.getBlockHash(), rcvTx.getAddress().getHash(),
                                            rcvTx.getValue(), rcvTx.isSpent(), rcvTx.isChange(),
                                            rcvTx.inSafe(), false, rcvTx.isCoinBase(),
                                            rcvTx.getScriptBytes());
            dbReceived.put(txID.getBytes(), rcvEntry.getBytes());
        } catch (DBException exc) {
            log.error(String.format("Unable to store receive transaction\n  %s",
                                    rcvTx.getTxHash().toString()), exc);
            throw new WalletException("Unable to store receive transaction", rcvTx.getTxHash());
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
        try {
            TransactionID txID = new TransactionID(txHash, txIndex);
            byte[] entryData = dbReceived.get(txID.getBytes());
            if (entryData != null) {
                ReceiveEntry rcvEntry = new ReceiveEntry(entryData);
                rcvEntry.setSpent(isSpent);
                dbReceived.put(txID.getBytes(), rcvEntry.getBytes());
            }
        } catch (DBException | EOFException exc) {
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
        try {
            TransactionID txID = new TransactionID(txHash, txIndex);
            byte[] entryData = dbReceived.get(txID.getBytes());
            if (entryData != null) {
                ReceiveEntry rcvEntry = new ReceiveEntry(entryData);
                rcvEntry.setSafe(inSafe);
                dbReceived.put(txID.getBytes(), rcvEntry.getBytes());
            }
        } catch (DBException | EOFException exc) {
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
        try {
            TransactionID txID = new TransactionID(txHash, txIndex);
            byte[] entryData = dbReceived.get(txID.getBytes());
            if (entryData != null) {
                ReceiveEntry rcvEntry = new ReceiveEntry(entryData);
                rcvEntry.setDelete(isDeleted);
                dbReceived.put(txID.getBytes(), rcvEntry.getBytes());
            }
        } catch (DBException | EOFException exc) {
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
        synchronized(lock) {
            try {
                Map<Sha256Hash, ReceiveTransaction> prevMap = new HashMap<>();
                try (DBIterator it = dbReceived.iterator()) {
                    it.seekToFirst();
                    while (it.hasNext()) {
                        Entry<byte[], byte[]> dbEntry = it.next();
                        TransactionID txID = new TransactionID(dbEntry.getKey());
                        ReceiveEntry rcvEntry = new ReceiveEntry(dbEntry.getValue());
                        if (rcvEntry.isDeleted())
                            continue;
                        ReceiveTransaction tx = new ReceiveTransaction(rcvEntry.getNormalizedID(),
                                                    txID.getTxHash(), txID.getTxIndex(),
                                                    rcvEntry.getTxTime(), rcvEntry.getBlockHash(),
                                                    new Address(rcvEntry.getAddress()),
                                                    rcvEntry.getValue(), rcvEntry.getScriptBytes(),
                                                    rcvEntry.isSpent(), rcvEntry.isChange(),
                                                    rcvEntry.isCoinBase(), rcvEntry.inSafe());
                        ReceiveTransaction prevTx = prevMap.get(tx.getNormalizedID());
                        if (prevTx != null) {
                            if (!tx.getBlockHash().equals(Sha256Hash.ZERO_HASH)) {
                                txList.remove(prevTx);
                                txList.add(tx);
                                prevMap.put(tx.getNormalizedID(), tx);
                            }
                        } else {
                            txList.add(tx);
                            prevMap.put(tx.getNormalizedID(), tx);
                        }
                    }
                }
            } catch (DBException | IOException exc) {
                log.error("Unable to get receive transaction list", exc);
                throw new WalletException("Unable to get receive transaction list");
            }
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
        try {
            SendEntry sendEntry = new SendEntry(sendTx.getNormalizedID(), sendTx.getTxTime(),
                                        sendTx.getBlockHash(), sendTx.getAddress().getHash(),
                                        sendTx.getValue(), sendTx.getFee(), false,
                                        sendTx.getTxData());
            dbSent.put(sendTx.getTxHash().getBytes(), sendEntry.getBytes());
        } catch (DBException exc) {
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
        try {
            byte[] entryData = dbSent.get(txHash.getBytes());
            if (entryData != null) {
                SendEntry sendEntry = new SendEntry(entryData);
                sendEntry.setDelete(isDeleted);
                dbSent.put(txHash.getBytes(), sendEntry.getBytes());
            }
        } catch (DBException | EOFException exc) {
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
        try {
            byte[] entryData = dbSent.get(txHash.getBytes());
            if (entryData != null) {
                SendEntry sendEntry = new SendEntry(entryData);
                sendTx = new SendTransaction(sendEntry.getNormalizedID(), txHash,
                                        sendEntry.getTxTime(), sendEntry.getBlockHash(),
                                        new Address(sendEntry.getAddress()), sendEntry.getValue(),
                                        sendEntry.getFee(), sendEntry.getTxData());

            }
        } catch (DBException | EOFException exc) {
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
        synchronized(lock) {
            try {
                Map<Sha256Hash, SendTransaction> prevMap = new HashMap<>();
                try (DBIterator it = dbSent.iterator()) {
                    it.seekToFirst();
                    while (it.hasNext()) {
                        Entry<byte[], byte[]> dbEntry = it.next();
                        Sha256Hash txHash = new Sha256Hash(dbEntry.getKey());
                        SendEntry sendEntry = new SendEntry(dbEntry.getValue());
                        if (sendEntry.isDeleted())
                            continue;
                        SendTransaction tx = new SendTransaction(sendEntry.getNormalizedID(), txHash,
                                                sendEntry.getTxTime(), sendEntry.getBlockHash(),
                                                new Address(sendEntry.getAddress()),
                                                sendEntry.getValue(), sendEntry.getFee(), sendEntry.getTxData());
                        SendTransaction prevTx = prevMap.get(tx.getNormalizedID());
                        if (prevTx != null) {
                            if (!tx.getBlockHash().equals(Sha256Hash.ZERO_HASH)) {
                                txList.remove(prevTx);
                                txList.add(tx);
                                prevMap.put(tx.getNormalizedID(), tx);
                            }
                        } else {
                            txList.add(tx);
                            prevMap.put(tx.getNormalizedID(), tx);
                        }
                    }
                }
            } catch (DBException | IOException exc) {
                log.error("Unable to get send transaction list", exc);
                throw new WalletException("Unable to get send transaction list");
            }
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
        synchronized(lock) {
            try {
                Sha256Hash blockHash = Sha256Hash.ZERO_HASH;
                byte[] entryData = dbSent.get(txHash.getBytes());
                if (entryData != null) {
                    SendEntry sendEntry = new SendEntry(entryData);
                    blockHash = sendEntry.getBlockHash();
                } else {
                    try (DBIterator it = dbReceived.iterator()) {
                        it.seek(txHash.getBytes());
                        if (it.hasNext()) {
                            Entry<byte[], byte[]> dbEntry = it.next();
                            TransactionID txID = new TransactionID(dbEntry.getKey());
                            if (txID.getTxHash().equals(txHash)) {
                                ReceiveEntry rcvEntry = new ReceiveEntry(dbEntry.getValue());
                                blockHash = rcvEntry.getBlockHash();
                            }
                        }
                    }
                }
                if (!blockHash.equals(Sha256Hash.ZERO_HASH)) {
                    entryData = dbHeaders.get(blockHash.getBytes());
                    if (entryData != null) {
                        BlockEntry blockEntry = new BlockEntry(entryData);
                        if (blockEntry.isOnChain())
                            txDepth = chainHeight - blockEntry.getHeight() + 1;
                    }
                }
            } catch (DBException | IOException exc) {
                log.error(String.format("Unable to get transaction depth\n  %s", txHash.toString()), exc);
                throw new WalletException("Unable to get transaction depth", txHash);
            }
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
        synchronized (lock) {
            //
            // Starting with the supplied block, follow the previous hash values until
            // we reach a block which is on the block chain.  This block is the junction
            // block.
            //
            try {
                while (!onChain) {
                    byte[] entryData = dbHeaders.get(blockHash.getBytes());
                    if (entryData != null) {
                        BlockEntry blockEntry = new BlockEntry(entryData);
                        onChain = blockEntry.isOnChain();
                        BlockHeader header = new BlockHeader(blockHash, blockEntry.getPrevHash(),
                                        blockEntry.getTimeStamp(), blockEntry.getTargetDifficulty(),
                                        blockEntry.getMerkleRoot(), onChain, blockEntry.getHeight(),
                                        blockEntry.getChainWork(), blockEntry.getMatches());
                        chainList.add(0, header);
                        blockHash = blockEntry.getPrevHash();
                    } else {
                        log.warn(String.format("Chain block is not available\n  %s", blockHash.toString()));
                        throw new BlockNotFoundException("Unable to resolve block chain", blockHash);
                    }
                }
            } catch (DBException | EOFException exc) {
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
        //
        // Make the new block the chain head
        //
        synchronized (lock) {
            Sha256Hash blockHash;
            Sha256Hash prevHash;
            BlockHeader header;
            Entry<byte[], byte[]> dbEntry;
            byte[] entryData;
            BlockEntry blockEntry;
            TransactionID txID;
            ReceiveEntry rcvEntry;
            SendEntry sendEntry;
            List<Sha256Hash> txList;
            try {
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
                        // Get the block from the Headers database
                        //
                        entryData = dbHeaders.get(blockHash.getBytes());
                        if (entryData == null) {
                            log.error(String.format("Chain block not found\n  %s", blockHash.toString()));
                            throw new WalletException("Chain block not found", blockHash);
                        }
                        blockEntry = new BlockEntry(entryData);
                        prevHash = blockEntry.getPrevHash();
                        txList = blockEntry.getMatches();
                        //
                        // Update the matched transactions to indicate they are no longer confirmed
                        //
                        if (txList != null) {
                            Map<TransactionID, ReceiveEntry> rcvMap = new HashMap<>();
                            for (Sha256Hash txHash : txList) {
                                //
                                // Update the Received database
                                //
                                try (DBIterator it = dbReceived.iterator()) {
                                    it.seek(txHash.getBytes());
                                    while (it.hasNext()) {
                                        dbEntry = it.next();
                                        txID = new TransactionID(dbEntry.getKey());
                                        if (!txID.getTxHash().equals(txHash))
                                            break;
                                        rcvEntry = new ReceiveEntry(dbEntry.getValue());
                                        rcvEntry.setBlockHash(Sha256Hash.ZERO_HASH);
                                        rcvMap.put(txID, rcvEntry);
                                    }
                                }
                                //
                                // Update the Sent database
                                //
                                entryData = dbSent.get(txHash.getBytes());
                                if (entryData != null) {
                                    sendEntry = new SendEntry(entryData);
                                    sendEntry.setBlockHash(Sha256Hash.ZERO_HASH);
                                    dbSent.put(txHash.getBytes(), sendEntry.getBytes());
                                }
                            }
                            //
                            // Process the Received database updates
                            //
                            Set<Entry<TransactionID, ReceiveEntry>> updates = rcvMap.entrySet();
                            Iterator<Entry<TransactionID, ReceiveEntry>> it = updates.iterator();
                            while (it.hasNext()) {
                                Entry<TransactionID, ReceiveEntry> entry = it.next();
                                dbReceived.put(entry.getKey().getBytes(), entry.getValue().getBytes());
                            }
                        }
                        //
                        // Remove the block from the chain
                        //
                        dbBlockChain.delete(getIntegerBytes(blockEntry.getHeight()));
                        blockEntry.setChain(false);
                        blockEntry.setHeight(0);
                        blockEntry.setChainWork(BigInteger.ZERO);
                        dbHeaders.put(blockHash.getBytes(), blockEntry.getBytes());
                        chainHeight = blockEntry.getHeight()-1;
                        chainHead = blockEntry.getPrevHash();
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
                    entryData = dbHeaders.get(blockHash.getBytes());
                    if (entryData == null) {
                        log.error(String.format("New chain block not found\n  %s", blockHash));
                        throw new WalletException("New chain block not found");
                    }
                    blockEntry = new BlockEntry(entryData);
                    //
                    // Update the sent and received transactions for this block to indicate
                    // they are now confirmed
                    //
                    if (txList != null) {
                        Map<TransactionID, ReceiveEntry> rcvMap = new HashMap<>();
                        for (Sha256Hash txHash : txList) {
                            //
                            // Update the Received database
                            //
                            try (DBIterator it = dbReceived.iterator()) {
                                it.seek(txHash.getBytes());
                                while (it.hasNext()) {
                                    dbEntry = it.next();
                                    txID = new TransactionID(dbEntry.getKey());
                                    if (!txID.getTxHash().equals(txHash))
                                        break;
                                    rcvEntry = new ReceiveEntry(dbEntry.getValue());
                                    rcvEntry.setBlockHash(blockHash);
                                    rcvMap.put(txID, rcvEntry);
                                }
                            }
                            //
                            // Update the Sent database
                            //
                            entryData = dbSent.get(txHash.getBytes());
                            if (entryData != null) {
                                sendEntry = new SendEntry(entryData);
                                sendEntry.setBlockHash(blockHash);
                                dbSent.put(txHash.getBytes(), sendEntry.getBytes());
                            }
                        }
                        //
                        // Process the Received database updates
                        //
                        Set<Entry<TransactionID, ReceiveEntry>> updates = rcvMap.entrySet();
                        Iterator<Entry<TransactionID, ReceiveEntry>> it = updates.iterator();
                        while (it.hasNext()) {
                            Entry<TransactionID, ReceiveEntry> entry = it.next();
                            dbReceived.put(entry.getKey().getBytes(), entry.getValue().getBytes());
                        }
                    }
                    //
                    // Update the block status
                    //
                    dbBlockChain.put(getIntegerBytes(blockHeight), blockHash.getBytes());
                    blockEntry.setChain(true);
                    blockEntry.setHeight(blockHeight);
                    blockEntry.setChainWork(blockWork);
                    dbHeaders.put(blockHash.getBytes(), blockEntry.getBytes());
                    chainHead = blockHash;
                    chainHeight = blockHeight;
                    chainWork = blockWork;
                    log.info(String.format("Block added to block chain at height %d\n  %s",
                                           blockHeight, blockHash.toString()));
                }
            } catch (DBException | IOException exc) {
                log.error("Unable to update block chain", exc);
                throw new WalletException("Unable to update block chain");
            }
        }
    }

    /**
     * Closes the database
     */
    @Override
    public void close() {
        try {
            if (dbBlockChain != null)
                dbBlockChain.close();
            if (dbHeaders != null)
                dbHeaders.close();
            if (dbChild != null)
                dbChild.close();
            if (dbReceived != null)
                dbReceived.close();
            if (dbSent != null)
                dbSent.close();
            if (dbAddress != null)
                dbAddress.close();
            if (dbKeys != null)
                dbKeys.close();
        } catch (DBException | IOException exc) {
            log.error("Unable to close database", exc);
        }
    }

    /**
     * Get the 4-byte key for an integer value.  The key uses big-endian format
     * since LevelDB uses a byte comparator to sort the keys.  This will result
     * in the keys being sorted by ascending value.
     *
     * @param       intVal          Integer value
     * @return                      4-byte array containing the integer
     */
    private byte[] getIntegerBytes(int intVal) {
        byte[] intBytes = new byte[4];
        intBytes[0] = (byte)(intVal>>>24);
        intBytes[1] = (byte)(intVal>>>16);
        intBytes[2] = (byte)(intVal>>>8);
        intBytes[3] = (byte)intVal;
        return intBytes;
    }

    /**
     * Get the integer value from the 4-byte key
     *
     * @param       key         Key bytes
     * @return                  Integer value
     */
    private int getInteger(byte[] key) {
        return (((int)key[0]&0xff)<<24) | (((int)key[1]&0xff)<<16) | (((int)key[2]&0xff)<<8) | ((int)key[3]&0xff);
    }

    /**
     * <p>The Headers database contains an entry for each block header.  This includes
     * chain blocks and orphan blocks.  The key is the block hash and the value is an
     * instance of BlockEntry.</p>
     *
     * <p>BlockEntry</p>
     * <pre>
     *   Size       Field               Description
     *   ====       =====               ===========
     *   1 byte     OnChain             Block is on the chain
     *  32 bytes    PrevHash            Previous block hash
     *  32 bytes    MerkleRoot          Merkle root
     *  VarBytes    ChainWork           Chain work
     *   VarInt     TimeStamp           Block timestamp
     *   VarInt     BlockHeight         Block height
     *   VarInt     TargetDifficulty    Target difficulty
     *  VarList     Matches             Matched transactions
     * </pre>
     */
    private class BlockEntry {

        /** Previous block hash */
        private Sha256Hash prevHash;

        /** Merkle root */
        private Sha256Hash merkleRoot;

        /** Block height */
        private int blockHeight;

        /** Chain work */
        private BigInteger chainWork;

        /** Block timestamp */
        private long timeStamp;

        /** Target difficulty */
        private long targetDifficulty;

        /** Block chain status */
        private boolean onChain;

        /** Matched transactions */
        private List<Sha256Hash> matches;

        /**
         * Creates a new BlockEntry
         *
         * @param       prevHash            Previous block hash
         * @param       timeStamp           Block timestamp
         * @param       targetDifficulty    Target difficulty
         * @param       merkleRoot          Merkle root
         * @param       onChain             TRUE if the block is on the chain
         * @param       blockHeight         Block height
         * @param       chainWork           Chain work
         * @param       matches             Matched transactions or null
         */
        public BlockEntry(Sha256Hash prevHash, long timeStamp, long targetDifficulty,
                                            Sha256Hash merkleRoot, boolean onChain,
                                            int blockHeight, BigInteger chainWork,
                                            List<Sha256Hash> matches) {
            this.prevHash = prevHash;
            this.timeStamp = timeStamp;
            this.targetDifficulty = targetDifficulty;
            this.merkleRoot = merkleRoot;
            this.onChain = onChain;
            this.blockHeight = blockHeight;
            this.chainWork = chainWork;
            this.matches = matches;
        }

        /**
         * Creates a new BlockEntry from the serialized entry data
         *
         * @param       entryData       Serialized entry data
         * @throws      EOFException    End-of-data processing the serialized data
         */
        public BlockEntry(byte[] entryData) throws EOFException {
            if (entryData.length < 65)
                throw new EOFException("End-of-data while processing BlockEntry");
            onChain = (entryData[0]==1);
            prevHash = new Sha256Hash(entryData, 1, 32);
            merkleRoot = new Sha256Hash(entryData, 33, 32);
            int offset = 65;
            // Decode chainWork
            VarInt varInt = new VarInt(entryData, offset);
            int length = varInt.toInt();
            offset += varInt.getEncodedSize();
            if (offset+length > entryData.length)
                throw new EOFException("End-of-data while processing BlockEntry");
            byte[] bytes = Arrays.copyOfRange(entryData, offset, offset+length);
            chainWork = new BigInteger(bytes);
            offset += length;
            // Decode timeStamp
            varInt = new VarInt(entryData, offset);
            timeStamp = varInt.toLong();
            offset += varInt.getEncodedSize();
            // Decode blockHeight
            varInt = new VarInt(entryData, offset);
            blockHeight = varInt.toInt();
            offset += varInt.getEncodedSize();
            // Decode targetDifficulty
            varInt = new VarInt(entryData, offset);
            targetDifficulty = varInt.toLong();
            offset += varInt.getEncodedSize();
            // Decode matches
            varInt = new VarInt(entryData, offset);
            int count = varInt.toInt();
            offset += varInt.getEncodedSize();
            if (count > 0) {
                if (offset+32*count > entryData.length)
                    throw new EOFException("End-of-data while processing BlockEntry");
                matches = new ArrayList<>(count);
                for (int i=0; i<count; i++) {
                    matches.add(new Sha256Hash(entryData, offset, 32));
                    offset += 32;
                }
            }
        }

        /**
         * Returns the serialized entry data
         *
         * @return      Serialized data stream
         */
        public byte[] getBytes() {
            byte[] heightData = VarInt.encode(blockHeight);
            byte[] workBytes = chainWork.toByteArray();
            byte[] workLength = VarInt.encode(workBytes.length);
            byte[] timeData = VarInt.encode(timeStamp);
            byte[] targetData = VarInt.encode(targetDifficulty);
            int length = 1+32+32+workLength.length+workBytes.length+timeData.length+
                                    heightData.length+targetData.length;
            byte[] countData;
            if (matches != null) {
                countData = VarInt.encode(matches.size());
                length += countData.length+matches.size()*32;
            } else {
                countData = new byte[1];
                length++;
            }
            byte[] entryData = new byte[length];
            entryData[0] = (onChain ? (byte)1 : 0);
            System.arraycopy(prevHash.getBytes(), 0, entryData, 1, 32);
            System.arraycopy(merkleRoot.getBytes(), 0, entryData, 33, 32);
            int offset = 65;
            // Encode chainWork
            System.arraycopy(workLength, 0, entryData, offset, workLength.length);
            offset += workLength.length;
            System.arraycopy(workBytes, 0, entryData, offset, workBytes.length);
            offset += workBytes.length;
            // Encode timestamp
            System.arraycopy(timeData, 0, entryData, offset, timeData.length);
            offset += timeData.length;
            // Encode blockHeight
            System.arraycopy(heightData, 0, entryData, offset, heightData.length);
            offset += heightData.length;
            // Encode targetDifficulty
            System.arraycopy(targetData, 0, entryData, offset, targetData.length);
            offset += targetData.length;
            // Encode matches
            System.arraycopy(countData, 0, entryData, offset, countData.length);
            offset += countData.length;
            if (matches != null) {
                for (Sha256Hash hash : matches) {
                    System.arraycopy(hash.getBytes(), 0, entryData, offset, 32);
                    offset += 32;
                }
            }
            return entryData;
        }

        /**
         * Returns the previous block hash
         *
         * @return      Block hash
         */
        public Sha256Hash getPrevHash() {
            return prevHash;
        }

        /**
         * Returns the merkle root
         *
         * @return      Merkle root
         */
        public Sha256Hash getMerkleRoot() {
            return merkleRoot;
        }

        /**
         * Returns the block timestamp
         *
         * @return      Block timestamp
         */
        public long getTimeStamp() {
            return timeStamp;
        }

        /**
         * Returns the target difficulty
         *
         * @return      Target difficulty
         */
        public long getTargetDifficulty() {
            return targetDifficulty;
        }

        /**
         * Returns the block height
         *
         * @return      Block height
         */
        public int getHeight() {
            return blockHeight;
        }

        /**
         * Sets the block height
         *
         * @param       blockHeight     Tne block height
         */
        public void setHeight(int blockHeight) {
            this.blockHeight = blockHeight;
        }

        /**
         * Returns the chain work
         *
         * @return      Chain work
         */
        public BigInteger getChainWork() {
            return chainWork;
        }

        /**
         * Sets the chain work
         *
         * @param       chainWork       Chain work
         */
        public void setChainWork(BigInteger chainWork) {
            this.chainWork = chainWork;
        }

        /**
         * Returns the block chain status
         *
         * @return      TRUE if the block is on the chain
         */
        public boolean isOnChain() {
            return onChain;
        }

        /**
         * Sets the block chain status
         *
         * @param       onChain         TRUE if the block is on the chain
         */
        public void setChain(boolean onChain) {
            this.onChain = onChain;
        }

        /**
         * Returns the matched transactions
         *
         * @return      List of matched transactions
         */
        public List<Sha256Hash> getMatches() {
            return matches;
        }

        /**
         * Sets the matched transactions
         *
         * @param       matches         List of matched transactions
         */
        public void setMatches(List<Sha256Hash> matches) {
            this.matches = matches;
        }
    }

    /**
     * TransactionID consists of the transaction hash plus the transaction output index
     */
    private class TransactionID {

        /** Transaction hash */
        private Sha256Hash txHash;

        /** Transaction output index */
        private int txIndex;

        /**
         * Creates the transaction ID
         *
         * @param       txHash          Transaction hash
         * @param       txIndex         Transaction output index
         */
        public TransactionID(Sha256Hash txHash, int txIndex) {
            this.txHash = txHash;
            this.txIndex = txIndex;
        }

        /**
         * Creates the transaction ID from the serialized key data
         *
         * @param       bytes           Serialized key data
         * @throws      EOFException    End-of-data reached
         */
        public TransactionID(byte[] bytes) throws EOFException {
            if (bytes.length < 33)
                throw new EOFException("End-of-data while processing TransactionID");
            txHash = new Sha256Hash(bytes, 0, 32);
            txIndex = new VarInt(bytes, 32).toInt();
        }

        /**
         * Returns the serialized transaction ID
         *
         * @return      Serialized transaction ID
         */
        public byte[] getBytes() {
            byte[] indexData = VarInt.encode(txIndex);
            byte[] bytes = new byte[32+indexData.length];
            System.arraycopy(txHash.getBytes(), 0, bytes, 0, 32);
            System.arraycopy(indexData, 0, bytes, 32, indexData.length);
            return bytes;
        }

        /**
         * Returns the transaction hash
         *
         * @return                  Transaction hash
         */
        public Sha256Hash getTxHash() {
            return txHash;
        }

        /**
         * Returns the transaction output index
         *
         * @return                  Transaction output index
         */
        public int getTxIndex() {
            return txIndex;
        }

        /**
         * Compares two objects
         *
         * @param       obj         Object to compare
         * @return                  TRUE if the objects are equal
         */
        @Override
        public boolean equals(Object obj) {
            boolean areEqual = false;
            if (obj != null && (obj instanceof TransactionID)) {
                TransactionID cmpObj = (TransactionID)obj;
                areEqual = (cmpObj.txHash.equals(txHash) && cmpObj.txIndex == txIndex);
            }
            return areEqual;
        }

        /**
         * Returns the hash code
         *
         * @return                  Hash code
         */
        @Override
        public int hashCode() {
            return txHash.hashCode();
        }
    }

    /**
     * <p>The Received database contains an entry for each receive transaction.
     * The key is a TransactionID and the value is a ReceiveEntry.</p>
     *
     * <p>ReceiveEntry</p>
     * <pre>
     *   Size       Field               Description
     *   ====       =====               ===========
     *   1 byte     IsSpent             Coins have been spent
     *   1 byte     IsChange            Coins received to a change address
     *   1 byte     InSafe              Coins are in the safe
     *   1 byte     IsDeleted           Transaction is deleted
     *   1 byte     IsCoinbase          Coinbase transaction
     *  32 bytes    NormID              Normalized identifier
     *  32 bytes    BlockHash           Block containing the transaction
     *  20 bytes    Address             Receive address
     *   VarInt     TxTime              Transaction time
     *  VarBytes    Value               Transaction value
     *  VarBytes    ScriptBytes         ScriptBytes
     * </pre>
     */
    private class ReceiveEntry {

        /** Normalized ID */
        private Sha256Hash normID;

        /** Block hash */
        private Sha256Hash blockHash;

        /** Transaction time */
        private long txTime;

        /** Receive address */
        private byte[] address;

        /** Transaction value */
        private BigInteger value;

        /** Coins spent */
        private boolean isSpent;

        /** Coins sent to change address */
        private boolean isChange;

        /** Coins are in the safe */
        private boolean inSafe;

        /** This is a coinbase transaction */
        private boolean isCoinBase;

        /** Transaction is deleted */
        private boolean isDeleted;

        /** Script bytes */
        private byte[] scriptBytes;

        /**
         * Creates a new ReceiveEntry
         *
         * @param       normID              Normalized ID
         * @param       txTime              Transaction time
         * @param       blockHash           Block containing the transaction
         * @param       address             Receive address
         * @param       value               Transaction value
         * @param       isSpent             Coins spent
         * @param       isChange            Coins sent to change address
         * @param       inSafe              Coins are in the safe
         * @param       isDeleted           Transaction is deleted
         * @param       isCoinBase          This is a coinbase transaction
         * @param       scriptBytes         Script bytes
         */
        public ReceiveEntry(Sha256Hash normID, long txTime, Sha256Hash blockHash,
                                            byte[] address, BigInteger value,
                                            boolean isSpent, boolean isChange, boolean inSafe,
                                            boolean isDeleted, boolean isCoinBase, byte[] scriptBytes) {
            this.normID = normID;
            this.txTime = txTime;
            this.blockHash = blockHash;
            this.address = address;
            this.value = value;
            this.isSpent = isSpent;
            this.isChange = isChange;
            this.inSafe = inSafe;
            this.isDeleted = isDeleted;
            this.isCoinBase = isCoinBase;
            this.scriptBytes = scriptBytes;
        }

        /**
         * Creates a new ReceiveEntry from the serialized entry data
         *
         * @param       entryData       Serialized entry data
         * @throws      EOFException    End-of-data processing the serialized data
         */
        public ReceiveEntry(byte[] entryData) throws EOFException {
            if (entryData.length < 89)
                throw new EOFException("End-of-data while processing ReceiveEntry");
            isSpent = (entryData[0]==1);
            isChange = (entryData[1]==1);
            inSafe = (entryData[2]==1);
            isDeleted = (entryData[3]==1);
            isCoinBase = (entryData[4]==1);
            normID = new Sha256Hash(entryData, 5, 32);
            blockHash = new Sha256Hash(entryData, 37, 32);
            address = Arrays.copyOfRange(entryData, 69, 89);
            int offset = 89;
            // Decode txTime
            VarInt varInt = new VarInt(entryData, offset);
            txTime = varInt.toLong();
            offset += varInt.getEncodedSize();
            // Decode value
            varInt = new VarInt(entryData, offset);
            int length = varInt.toInt();
            offset += varInt.getEncodedSize();
            if (offset+length > entryData.length)
                throw new EOFException("End-of-data while processing ReceiveEntry");
            byte[] bytes = Arrays.copyOfRange(entryData, offset, offset+length);
            value = new BigInteger(bytes);
            offset += length;
            // Decode scriptBytes
            varInt = new VarInt(entryData, offset);
            length = varInt.toInt();
            offset += varInt.getEncodedSize();
            if (offset+length > entryData.length)
                throw new EOFException("End-of-data while processing ReceiveEntry");
            scriptBytes = Arrays.copyOfRange(entryData, offset, offset+length);
        }

        /**
         * Returns the serialized entry data
         *
         * @return      Serialized data stream
         */
        public byte[] getBytes() {
            byte[] timeData = VarInt.encode(txTime);
            byte[] valueData = value.toByteArray();
            byte[] valueLength = VarInt.encode(valueData.length);
            byte[] scriptLength = VarInt.encode(scriptBytes.length);
            byte[] entryData = new byte[5+32+32+20+timeData.length+valueLength.length+valueData.length+
                                        scriptLength.length+scriptBytes.length];
            entryData[0] = (isSpent?(byte)1:0);
            entryData[1] = (isChange?(byte)1:0);
            entryData[2] = (inSafe?(byte)1:0);
            entryData[3] = (isDeleted?(byte)1:0);
            entryData[4] = (isCoinBase?(byte)1:0);
            System.arraycopy(normID.getBytes(), 0, entryData, 5, 32);
            System.arraycopy(blockHash.getBytes(), 0, entryData, 37, 32);
            System.arraycopy(address, 0, entryData, 69, 20);
            int offset = 89;
            // Encode txTime
            System.arraycopy(timeData, 0, entryData, offset, timeData.length);
            offset += timeData.length;
            // Encode value
            System.arraycopy(valueLength, 0, entryData, offset, valueLength.length);
            offset += valueLength.length;
            System.arraycopy(valueData, 0, entryData, offset, valueData.length);
            offset += valueData.length;
            // Encode scriptBytes
            System.arraycopy(scriptLength, 0, entryData, offset, scriptLength.length);
            offset += scriptLength.length;
            System.arraycopy(scriptBytes, 0, entryData, offset, scriptBytes.length);
            return entryData;
        }

        /**
         * Returns the normalized ID
         *
         * @return      Normalized ID
         */
        public Sha256Hash getNormalizedID() {
            return normID;
        }

        /**
         * Returns the block hash
         *
         * @return      Block hash
         */
        public Sha256Hash getBlockHash() {
            return blockHash;
        }

        /**
         * Sets the block hash
         *
         * @param       blockHash           Block hash
         */
        public void setBlockHash(Sha256Hash blockHash) {
            this.blockHash = blockHash;
        }

        /**
         * Returns the transaction time
         *
         * @return      Transaction time
         */
        public long getTxTime() {
            return txTime;
        }

        /**
         * Returns the receive address
         *
         * @return      Receive address
         */
        public byte[] getAddress() {
            return address;
        }

        /**
         * Returns the transaction value
         *
         * @return      Transaction value
         */
        public BigInteger getValue() {
            return value;
        }

        /**
         * Returns the script bytes
         *
         * @return      Script bytes
         */
        public byte[] getScriptBytes() {
            return scriptBytes;
        }

        /**
         * Checks if coins are spent
         *
         * @return      TRUE if the coins are spent
         */
        public boolean isSpent() {
            return isSpent;
        }

        /**
         * Sets the spent status
         *
         * @param       isSpent     TRUE if the coins have been spent
         */
        public void setSpent(boolean isSpent) {
            this.isSpent = isSpent;
        }

        /**
         * Checks if coins are in the safe
         *
         * @return      TRUE if the coins are in the safe
         */
        public boolean inSafe() {
            return inSafe;
        }

        /**
         * Sets the safe status
         *
         * @param       inSafe      TRUE if the coins are in the safe
         */
        public void setSafe(boolean inSafe) {
            this.inSafe = inSafe;
        }

        /**
         * Checks if the coins were sent to a receive address
         *
         * @return      TRUE if sent to a receive address
         */
        public boolean isChange() {
            return isChange;
        }

        /**
         * Checks if this is a coinbase transaction
         *
         * @return      TRUE if coinbase
         */
        public boolean isCoinBase() {
            return isCoinBase;
        }

        /**
         * Checks if the transaction is deleted
         *
         * @return      True if deleted
         */
        public boolean isDeleted() {
            return isDeleted;
        }

        /**
         * Sets the delete status
         *
         * @param       isDeleted       TRUE if the transaction is deleted
         */
        public void setDelete(boolean isDeleted) {
            this.isDeleted = isDeleted;
        }
    }

    /**
     * <p>The Sent database contains an entry for each send transaction.
     * The key is a transaction hash and the value is a SendEntry.</p>
     *
     * <p>SendEntry</p>
     * <pre>
     *   Size       Field               Description
     *   ====       =====               ===========
     *   1 byte     IsDeleted           Transaction is deleted
     *  32 bytes    NormID              Normalized identifier
     *  32 bytes    BlockHash           Block containing the transaction
     *  20 bytes    Address             Send address
     *   VarInt     TxTime              Transaction time
     *  VarBytes    Value               Transaction value
     *  VarBytes    Fee                 Transaction fee
     *  VarBytes    TxData              Transaction data
     * </pre>
     */
    private class SendEntry {

        /** Normalized ID */
        private Sha256Hash normID;

        /** Block hash */
        private Sha256Hash blockHash;

        /** Transaction time */
        private long txTime;

        /** Send address */
        private byte[] address;

        /** Transaction value */
        private BigInteger value;

        /** Transaction fee */
        private BigInteger fee;

        /** Transaction is deleted */
        private boolean isDeleted;

        /** Transaction data */
        private byte[] txData;

        /**
         * Creates a new SendEntry
         *
         * @param       normID              Normalized ID
         * @param       txTime              Transaction time
         * @param       blockHash           Block containing the transaction
         * @param       address             Receive address
         * @param       value               Transaction value
         * @param       fee                 Transaction fee
         * @param       isDeleted           Transaction is deleted
         * @param       txData              Transaction data
         */
        public SendEntry(Sha256Hash normID, long txTime, Sha256Hash blockHash, byte[] address,
                                            BigInteger value, BigInteger fee, boolean isDeleted,
                                            byte[] txData) {
            this.normID = normID;
            this.txTime = txTime;
            this.blockHash = blockHash;
            this.address = address;
            this.value = value;
            this.fee = fee;
            this.isDeleted = isDeleted;
            this.txData = txData;
        }

        /**
         * Creates a new SendEntry from the serialized entry data
         *
         * @param       entryData       Serialized entry data
         * @throws      EOFException    End-of-data processing the serialized data
         */
        public SendEntry(byte[] entryData) throws EOFException {
            if (entryData.length < 85)
                throw new EOFException("End-of-data while processing SendEntry");
            isDeleted = (entryData[0]==1);
            normID = new Sha256Hash(entryData, 1, 32);
            blockHash = new Sha256Hash(entryData, 33, 32);
            address = Arrays.copyOfRange(entryData, 65, 85);
            int offset = 85;
            // Decode txTime
            VarInt varInt = new VarInt(entryData, offset);
            txTime = varInt.toLong();
            offset += varInt.getEncodedSize();
            // Decode value
            varInt = new VarInt(entryData, offset);
            int length = varInt.toInt();
            offset += varInt.getEncodedSize();
            if (offset+length > entryData.length)
                throw new EOFException("End-of-data while processing SendEntry");
            byte[] bytes = Arrays.copyOfRange(entryData, offset, offset+length);
            value = new BigInteger(bytes);
            offset += length;
            // Decode fee
            varInt = new VarInt(entryData, offset);
            length = varInt.toInt();
            offset += varInt.getEncodedSize();
            if (offset+length > entryData.length)
                throw new EOFException("End-of-data while processing SendEntry");
            bytes = Arrays.copyOfRange(entryData, offset, offset+length);
            fee = new BigInteger(bytes);
            offset += length;
            // Decode txData
            varInt = new VarInt(entryData, offset);
            length = varInt.toInt();
            offset += varInt.getEncodedSize();
            if (offset+length > entryData.length)
                throw new EOFException("End-of-data while processing SendEntry");
            txData = Arrays.copyOfRange(entryData, offset, offset+length);
        }

        /**
         * Returns the serialized entry data
         *
         * @return      Serialized data stream
         */
        public byte[] getBytes() {
            byte[] timeData = VarInt.encode(txTime);
            byte[] valueData = value.toByteArray();
            byte[] valueLength = VarInt.encode(valueData.length);
            byte[] feeData = fee.toByteArray();
            byte[] feeLength = VarInt.encode(feeData.length);
            byte[] txLength = VarInt.encode(txData.length);
            byte[] entryData = new byte[1+32+32+20+timeData.length+valueLength.length+valueData.length+
                                        feeLength.length+feeData.length+txLength.length+txData.length];
            entryData[0] = (isDeleted?(byte)1:0);
            System.arraycopy(normID.getBytes(), 0, entryData, 1, 32);
            System.arraycopy(blockHash.getBytes(), 0, entryData, 33, 32);
            System.arraycopy(address, 0, entryData, 65, 20);
            int offset = 85;
            // Encode txTime
            System.arraycopy(timeData, 0, entryData, offset, timeData.length);
            offset += timeData.length;
            // Encode value
            System.arraycopy(valueLength, 0, entryData, offset, valueLength.length);
            offset += valueLength.length;
            System.arraycopy(valueData, 0, entryData, offset, valueData.length);
            offset += valueData.length;
            // Encode fee
            System.arraycopy(feeLength, 0, entryData, offset, feeLength.length);
            offset += feeLength.length;
            System.arraycopy(feeData, 0, entryData, offset, feeData.length);
            offset += feeData.length;
            // Encode txData
            System.arraycopy(txLength, 0, entryData, offset, txLength.length);
            offset += txLength.length;
            System.arraycopy(txData, 0, entryData, offset, txData.length);
            return entryData;
        }

        /**
         * Returns the normalized ID
         *
         * @return      Normalized ID
         */
        public Sha256Hash getNormalizedID() {
            return normID;
        }

        /**
         * Returns the block hash
         *
         * @return      Block hash
         */
        public Sha256Hash getBlockHash() {
            return blockHash;
        }

        /**
         * Sets the block hash
         *
         * @param       blockHash           Block hash
         */
        public void setBlockHash(Sha256Hash blockHash) {
            this.blockHash = blockHash;
        }

        /**
         * Returns the transaction time
         *
         * @return      Transaction time
         */
        public long getTxTime() {
            return txTime;
        }

        /**
         * Returns the send address
         *
         * @return      send address
         */
        public byte[] getAddress() {
            return address;
        }

        /**
         * Returns the transaction value
         *
         * @return      Transaction value
         */
        public BigInteger getValue() {
            return value;
        }

        /**
         * Returns the transaction fee
         *
         * @return      Transaction fee
         */
        public BigInteger getFee() {
            return fee;
        }

        /**
         * Returns the transaction data
         *
         * @return      Transaction data
         */
        public byte[] getTxData() {
            return txData;
        }

        /**
         * Checks if the transaction is deleted
         *
         * @return      True if deleted
         */
        public boolean isDeleted() {
            return isDeleted;
        }

        /**
         * Sets the delete status
         *
         * @param       isDeleted       TRUE if the transaction is deleted
         */
        public void setDelete(boolean isDeleted) {
            this.isDeleted = isDeleted;
        }
    }

    /**
     * <p>The Key database contains an entry for each receive key.
     * The key is the public key and the value is KeyEntry.
     *
     * <p>KeyEntry</p>
     * <pre>
     *   Size       Field               Description
     *   ====       =====               ===========
     *   1 byte     IsChange            This is a change key
     *   VarInt     CreationTime        Key creation time
     *  VarBytes    EncPrivKey          Encrypted private key
     *  VarString   Label               Key label
     * </pre>
     */
    private class KeyEntry {

        /** Encrypted private key */
        private byte[] encPrivKey;

        /** Key creation time */
        private long creationTime;

        /** Key label */
        private String label;

        /** Key is a change key */
        private boolean isChange;

        /**
         * Creates a new KeyEntry
         *
         * @param       encPrivKey          Encrypted private key
         * @param       creationTime        Key creation time
         * @param       label               Key label
         * @param       isChange            TRUE if this is a change key
         */
        public KeyEntry(byte[] encPrivKey, long creationTime, String label, boolean isChange) {
            this.encPrivKey = encPrivKey;
            this.creationTime = creationTime;
            this.label = label;
            this.isChange = isChange;
        }

        /**
         * Creates a new KeyEntry from the serialized entry data
         *
         * @param       entryData       Serialized entry data
         * @throws      EOFException    End-of-data processing the serialized data
         */
        public KeyEntry(byte[] entryData) throws EOFException {
            if (entryData.length < 2)
                throw new EOFException("End-of-data while processing KeyEntry");
            isChange = (entryData[0]==1);
            int offset = 1;
            // Decode creation time
            VarInt varInt = new VarInt(entryData, offset);
            creationTime = varInt.toLong();
            offset += varInt.getEncodedSize();
            // Decode encPrivKey
            varInt = new VarInt(entryData, offset);
            int length = varInt.toInt();
            offset += varInt.getEncodedSize();
            if (offset+length > entryData.length)
                throw new EOFException("End-of-data while processing KeyEntry");
            encPrivKey = Arrays.copyOfRange(entryData, offset, offset+length);
            offset += length;
            // Decode label
            varInt = new VarInt(entryData, offset);
            length = varInt.toInt();
            offset += varInt.getEncodedSize();
            if (length == 0) {
                label = "";
            } else {
                if (offset+length > entryData.length)
                    throw new EOFException("End-of-data while processing KeyEntry");
                label = new String(entryData, offset, length, charset);
            }
        }

        /**
         * Returns the serialized entry data
         *
         * @return      Serialized data stream
         */
        public byte[] getBytes() {
            byte[] timeData = VarInt.encode(creationTime);
            byte[] keyLength = VarInt.encode(encPrivKey.length);
            byte[] labelData = label.getBytes(charset);
            byte[] labelLength = VarInt.encode(labelData.length);
            byte[] entryData = new byte[1+timeData.length+keyLength.length+encPrivKey.length+
                                        labelLength.length+labelData.length];
            entryData[0] = (isChange?(byte)1:0);
            int offset = 1;
            // Encode creationTime
            System.arraycopy(timeData, 0, entryData, offset, timeData.length);
            offset += timeData.length;
            // Encode encPrivKey
            System.arraycopy(keyLength, 0, entryData, offset, keyLength.length);
            offset += keyLength.length;
            System.arraycopy(encPrivKey, 0, entryData, offset, encPrivKey.length);
            offset += encPrivKey.length;
            // Encode label
            System.arraycopy(labelLength, 0, entryData, offset, labelLength.length);
            offset += labelLength.length;
            if (labelData.length > 0) {
                System.arraycopy(labelData, 0, entryData, offset, labelData.length);
                offset += labelData.length;
            }
            return entryData;
        }

        /**
         * Returns the encrypted private key
         *
         * @return          Encrypted private key
         */
        public byte[] getPrivKey() {
            return encPrivKey;
        }

        /**
         * Returns the key creation time
         *
         * @return          Key creation time
         */
        public long getCreationTime() {
            return creationTime;
        }

        /**
         * Returns the key label
         *
         * @return          Key label
         */
        public String getLabel() {
            return label;
        }

        /**
         * Sets the key label
         *
         * @param       Key         Key label
         */
        public void setLabel(String label) {
            this.label = label;
        }

        /**
         * Returns the change status
         *
         * @return          TRUE if this is a change key
         */
        public boolean isChange() {
            return isChange;
        }
    }
}
