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

import java.util.HashMap;
import java.util.Map;

/**
 * Peer requests are tracked from the time they are submitted until the response
 * has been processed.  This allows requests to re-issued if a peer does not have
 * the requested item and also prevents duplicate requests for the same item.
 */
public class PeerRequest {

    /** Peer that sent the 'inv' message */
    private Peer origin;

    /** The block or transaction hash */
    private Sha256Hash hash;

    /** The inventory type */
    private int type;

    /** Map of peers that have been contacted for this request */
    private Map<Peer, Peer> peerMap = new HashMap<>(25);

    /** Timestamp */
    private long timeStamp;

    /** Request is being processed */
    private boolean processing;

    /**
     * Creates a new peer request
     *
     * @param       hash            The transaction or block hash
     * @param       type            The inventory type (INV_FILTERED_BLOCK or INV_TX)
     */
    public PeerRequest(Sha256Hash hash, int type) {
        this(hash, type, null);
    }

    /**
     * Creates a new peer request
     *
     * @param       hash            The transaction or block hash
     * @param       type            The inventory type (INV_FILTERED_BLOCK or INV_TX)
     * @param       origin          Peer that sent the 'inv' message
     */
    public PeerRequest(Sha256Hash hash, int type, Peer origin) {
        this.hash = hash;
        this.type = type;
        this.origin = origin;
    }

    /**
     * Returns the origin for this request
     *
     * @return      Peer that sent the 'inv' message or null if not an 'inv' request
     */
    public Peer getOrigin() {
        return origin;
    }

    /**
     * Returns the block or transaction hash
     *
     * @return      Block or transaction hash
     */
    public Sha256Hash getHash() {
        return hash;
    }

    /**
     * Returns the inventory type
     *
     * @return      Inventory type (INV_BLOCK or INV_TX)
     */
    public int getType() {
        return type;
    }

    /**
     * Returns the request timestamp
     *
     * @return      Request timestamp
     */
    public long getTimeStamp() {
        return timeStamp;
    }

    /**
     * Sets the request timestamp
     *
     * @param       timeStamp       Request timestamp
     */
    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    /**
     * Checks if a peer has already been contacted for this request
     *
     * @return      TRUE if the peer has been contacted
     */
    public boolean wasContacted(Peer peer) {
        return (peerMap.get(peer)!=null ? true : false);
    }

    /**
     * Indicates that a peer has been contacted
     *
     * @param       peer            The peer that has been contacted
     */
    public void addPeer(Peer peer) {
        if (peerMap.get(peer) == null)
            peerMap.put(peer, peer);
    }

    /**
     * Checks if the request is being processed
     *
     * @return      TRUE if the request is being processed
     */
    public boolean isProcessing() {
        return processing;
    }

    /**
     * Sets request as being processed
     *
     * @param       isProcessing        TRUE if the request is being processed
     */
    public void setProcessing(boolean isProcessing) {
        processing = isProcessing;
    }

    /**
     * Returns the hash code for this object
     *
     * @return      Hash code
     */
    @Override
    public int hashCode() {
        return hash.hashCode()^type;
    }

    /**
     * Checks if two objects are equal
     *
     * @param       obj             The object to compare
     * @return      TRUE if the objects are equal
     */
    @Override
    public boolean equals(Object obj) {
        boolean areEqual = false;
        if (obj != null && (obj instanceof PeerRequest)) {
            PeerRequest reqObj = (PeerRequest)obj;
            areEqual = (hash.equals(reqObj.hash) && type == reqObj.type);
        }
        return areEqual;
    }
}
