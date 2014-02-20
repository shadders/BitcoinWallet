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
 * A wallet transaction either receives coins (ReceiveTransaction) or sends coins (SendTransaction)
 */
public class WalletTransaction {

    /** Normalized transaction ID */
    protected Sha256Hash normID;

    /** Transaction hash */
    protected Sha256Hash txHash;

    /** Transaction timestamp */
    protected long txTime;

    /** Block hash for the chain block containing the transaction */
    protected Sha256Hash blockHash;

    /** Receiving address */
    protected Address address;

    /** Transaction value */
    protected BigInteger value;

    /** Transaction is deleted */
    protected boolean isDeleted;

    /**
     * Creates a wallet transaction
     *
     * @param       normID              Normalized transactionID
     * @param       txHash              Transaction hash
     * @param       txTime              Transaction timestamp
     * @param       blockHash           Chain block containing the transaction
     * @param       address             Send address
     * @param       value               Transaction value
     * @param       isDeleted           TRUE if transaction is deleted
     */
    public WalletTransaction(Sha256Hash normID, Sha256Hash txHash, long txTime, Sha256Hash blockHash,
                                Address address, BigInteger value, boolean isDeleted) {
        this.normID = normID;
        this.txHash = txHash;
        this.txTime = txTime;
        this.blockHash = blockHash;
        this.address = address;
        this.value = value;
        this.isDeleted = isDeleted;
    }

    /**
     * Returns the normalized transaction ID
     *
     * @return                          Normalized transaction ID
     */
    public Sha256Hash getNormalizedID() {
        return normID;
    }

    /**
     * Returns the transaction hash
     *
     * @return                          Transaction hash
     */
    public Sha256Hash getTxHash() {
        return txHash;
    }

    /**
     * Returns the transaction timestamp
     *
     * @return                          Transaction timestamp
     */
    public long getTxTime() {
        return txTime;
    }

    /**
     * Returns the block hash
     *
     * @return                          Block hash
     */
    public Sha256Hash getBlockHash() {
        return blockHash;
    }

    /**
     * Returns the send address
     *
     * @return                          Send address
     */
    public Address getAddress() {
        return address;
    }

    /**
     * Returns the transaction value
     *
     * @return                          Value
     */
    public BigInteger getValue() {
        return value;
    }

    /**
     * Checks if the transaction is deleted
     *
     * @return                          TRUE if the transaction is deleted
     */
    public boolean isDeleted() {
        return isDeleted;
    }

    /**
     * Sets the transaction delete status
     *
     * @param       isDeleted           TRUE if the transaction is deleted
     */
    public void setDelete(boolean isDeleted) {
        this.isDeleted = isDeleted;
    }
}
