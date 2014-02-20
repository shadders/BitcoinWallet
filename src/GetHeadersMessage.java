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

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

/**
 * <p>The 'getheaders' request returns a list of block headers.  This is similar to
 * the 'getblocks' request and is used by SPV clients who don't need the entire block.
 * The response is a 'headers' message containing up to 2000 block headers.</p>
 *
 * <p>GetHeaders Message</p>
 * <pre>
 *   Size       Field               Description
 *   ====       =====               ===========
 *   4 bytes    Version             Negotiated protocol version
 *   VarInt     Count               Number of locator hash entries
 *   Variable   Entries             Locator hash entries
 *  32 bytes    Stop                Hash of the last desired block or zero to get as many as possible
 * </pre>
 */
public class GetHeadersMessage {

    /**
     * Build a 'getheaderss' message
     *
     * We will request block headers starting with the current chain head and working backwards to
     * a maximum depth of 500 blocks.
     *
     * @param       peer            Destination peer
     * @return                      Message to send to the peer
     */
    public static Message buildGetHeadersMessage(Peer peer) {
        List<Sha256Hash> invList = new LinkedList<>();
        try {
            //
            // Get the chain list
            //
            int chainHeight = Parameters.wallet.getChainHeight();
            int blockHeight = Math.max(0, chainHeight-500);
            List<Sha256Hash> chainList = Parameters.wallet.getChainList(blockHeight, Sha256Hash.ZERO_HASH);
            //
            // Build the locator list starting with the chain head and working backwards towards
            // the genesis block
            //
            int step = 1;
            int loop = 0;
            int pos = chainList.size()-1;
            while (pos >= 0) {
                invList.add(chainList.get(pos));
                if (loop == 10) {
                    step = step*2;
                    pos = pos-step;
                } else {
                    loop++;
                    pos--;
                }
            }
        } catch (WalletException exc) {
            //
            // We can't query the database, so just locate the chain head and hope we
            // are on the main chain
            //
            invList.add(Parameters.wallet.getChainHead());
        }
        //
        // Build the message payload
        //
        // The protocol version will be set to the lesser of our version and the peer version
        // The stop locator will be set to zero since we don't know the network chain head.
        //
        int varCount = invList.size();
        byte[] varBytes = VarInt.encode(varCount);
        byte[] msgData = new byte[4+varBytes.length+varCount*32+32];
        Utils.uint32ToByteArrayLE(Math.min(Parameters.PROTOCOL_VERSION, peer.getVersion()), msgData, 0);
        System.arraycopy(varBytes, 0, msgData, 4, varBytes.length);
        int offset = 4+varBytes.length;
        for (Sha256Hash blockHash : invList) {
            System.arraycopy(Utils.reverseBytes(blockHash.getBytes()), 0, msgData, offset, 32);
            offset+=32;
        }
        //
        // Build the message
        //
        ByteBuffer buffer = MessageHeader.buildMessage("getheaders", msgData);
        return new Message(buffer, peer, MessageHeader.GETHEADERS_CMD);
    }
}
