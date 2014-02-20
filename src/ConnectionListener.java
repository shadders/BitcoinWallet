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
 * A connection listener receives notification when a connection starts or ends
 */
public interface ConnectionListener {

    /**
     * Notifies when a connection is started
     *
     * @param       peer            Remote peer
     */
    public void connectionStarted(Peer peer);

    /**
     * Notifies when a connection is terminated
     *
     * @param       peer            Remote peer
     */
    public void connectionEnded(Peer peer);
}
