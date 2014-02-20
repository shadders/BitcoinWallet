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

/**
 * A ReceiveTransction represents coins sent to the wallet and is created
 * from a transaction output that references one of the wallet addresses.
 */
public class ReceiveTransaction extends WalletTransaction {

    /** Transaction output index */
    private int txIndex;

    /** Transaction output script bytes */
    private byte[] scriptBytes;

    /** Transaction spent status */
    private boolean isSpent;

    /** Transaction is change returned for a send transaction */
    private boolean isChange;

    /** Transaction is a coinbase transaction */
    private boolean isCoinBase;

    /** Transaction is in the safe */
    private boolean inSafe;

    /**
     * Creates a ReceiveTransaction
     *
     * @param       normID              Normalized transaction ID
     * @param       txHash              Transaction output hash
     * @param       txIndex             Transaction output index
     * @param       txTime              Transaction timestamp
     * @param       blockHash           Chain block containing the transaction
     * @param       address             Receiving address
     * @param       value               Transaction value
     * @param       scriptBytes         Transaction output script bytes
     * @param       isChange            TRUE if change transaction
     * @param       isCoinBase          TRUE if coinbase transaction
     */
    public ReceiveTransaction(Sha256Hash normID, Sha256Hash txHash, int txIndex, long txTime,
                            Sha256Hash blockHash, Address address, BigInteger value, byte[] scriptBytes,
                            boolean isChange, boolean isCoinBase) {
        this(normID, txHash, txIndex, txTime, blockHash, address, value, scriptBytes,
                            false, isChange, isCoinBase, false, false);
    }

    /**
     * Creates a ReceiveTransaction
     *
     * @param       normID              Normalized transaction ID
     * @param       txHash              Transaction output hash
     * @param       txIndex             Transaction output index
     * @param       txTime              Transaction timestamp
     * @param       blockHash           Chain block containing the transaction
     * @param       address             Receiving address
     * @param       value               Transaction value
     * @param       scriptBytes         Transaction output script bytes
     * @param       isSpent             TRUE if transaction value has been spent
     * @param       isChange            TRUE if the transaction represents change from a send transaction
     * @param       isCoinBase          TRUE if coinbase transaction
     * @param       inSafe              TRUE if the transaction is in the safe
     * @param       isDeleted           TRUE if the transaction is deleted
     */
    public ReceiveTransaction(Sha256Hash normID, Sha256Hash txHash, int txIndex, long txTime,
                                Sha256Hash blockHash, Address address, BigInteger value, byte[] scriptBytes,
                                boolean isSpent, boolean isChange, boolean isCoinBase, boolean inSafe,
                                boolean isDeleted) {
        super(normID, txHash, txTime, blockHash, address, value, isDeleted);
        this.txIndex = txIndex;
        this.scriptBytes = scriptBytes;
        this.isSpent = isSpent;
        this.isChange = isChange;
        this.isCoinBase = isCoinBase;
        this.inSafe = inSafe;
    }

    /**
     * Returns the transaction output index
     *
     * @return                          Transaction output index
     */
    public int getTxIndex() {
        return txIndex;
    }

    /**
     * Returns the transaction output script bytes
     *
     * @return                          Script bytes
     */
    public byte[] getScriptBytes() {
        return scriptBytes;
    }

    /**
     * Checks if the transaction value has been spent
     *
     * @return                          TRUE if value has been spent
     */
    public boolean isSpent() {
        return isSpent;
    }

    /**
     * Sets the transaction spent status
     *
     * @param       isSpent             TRUE if the value has been spent
     */
    public void setSpent(boolean isSpent) {
        this.isSpent = isSpent;
    }

    /**
     * Checks if this is a change transaction
     *
     * @return                          TRUE if this is a change transaction
     */
    public boolean isChange() {
        return isChange;
    }

    /**
     * Checks if this is a coinbase transaction
     *
     * @return                          TRUE if this is a coinbase transaction
     */
    public boolean isCoinBase() {
        return isCoinBase;
    }

    /**
     * Checks if the transaction is in the safe
     *
     * @return                          TRUE if the transaction is in the safe
     */
    public boolean inSafe() {
        return inSafe;
    }

    /**
     * Sets the transaction safe status
     *
     * @param       inSafe              TRUE if the transaction is in the safe
     */
    public void setSafe(boolean inSafe) {
        this.inSafe = inSafe;
    }
}
