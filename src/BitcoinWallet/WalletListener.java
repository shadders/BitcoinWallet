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

/**
 * A wallet listener receives notifications when a new block or transaction
 * is received.
 */
public interface WalletListener {

    /**
     * Notification when a block is added to the chain
     *
     * @param       blockHeader     Block header
     */
    public void addChainBlock(BlockHeader blockHeader);

    /**
     * Notification when one or more transactions have been updated
     */
    public void txUpdated();
}
