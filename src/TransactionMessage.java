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

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * <p>The 'tx' message contains a transaction which is not yet in a block.  The transaction
 * will be held in the memory pool for a period of time to allow other peers to request
 * the transaction.</p>
 *
 * <p>Transaction Message</p>
 * <pre>
 *   Size           Field               Description
 *   ====           =====               ===========
 *   4 bytes        Version             Transaction version
 *   VarInt         InputCount          Number of inputs
 *   Variable       InputList           Inputs
 *   VarInt         OutputCount         Number of outputs
 *   Variable       OutputList          Outputs
 *   4 bytes        LockTime            Transaction lock time
 * </pre>
 */
public class TransactionMessage {

    /**
     * Processes a 'tx' message
     *
     * @param       msg                     Message
     * @param       inStream                Message data stream
     * @throws      EOFException            Serialized data is too short
     * @throws      IOException             Error reading input stream
     * @throws      VerificationException   Transaction verification failed
     */
    public static void processTransactionMessage(Message msg, ByteArrayInputStream inStream)
                                    throws EOFException, IOException, VerificationException {
        //
        // Get the transaction
        //
        int length = inStream.available();
        byte[] msgData = new byte[length];
        inStream.read(msgData);
        SerializedInputStream txStream = new SerializedInputStream(msgData, 0, length);
        Transaction tx = new Transaction(txStream);
        Sha256Hash txHash = tx.getHash();
        //
        // Remove the request from the processedRequests list
        //
        synchronized(Parameters.lock) {
            Iterator<PeerRequest> it = Parameters.processedRequests.iterator();
            while (it.hasNext()) {
                PeerRequest request = it.next();
                if (request.getType()==Parameters.INV_TX && request.getHash().equals(txHash)) {
                    it.remove();
                    break;
                }
            }
        }
        //
        // Add the transaction to the database queue
        //
        try {
            Parameters.databaseQueue.put(tx);
        } catch (InterruptedException exc) {
            // Should never happen
        }
    }
}
