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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * The network handler creates outbound connections and adds them to the
 * network selector.  A new outbound connection will be created whenever an
 * existing outbound connection is closed.  If specific peer addresses were specified,
 * then only those peers will be used for outbound connections.  Otherwise, DNS
 * discovery and peer broadcasts will be used to find available peers.
 *
 * When a message is received from a peer node, it is processed by a message
 * handler executing on a separate thread.  The message handler processes the
 * message and then creates a response message to be returned to the originating node.
 *
 * The network handler terminates when its shutdown() method is called.
 */
public class NetworkHandler implements Runnable {

    /** Logger instance */
    private static final Logger log = LoggerFactory.getLogger(NetworkHandler.class);

    /** Number of outbound connections */
    private static final int MAX_OUTBOUND_CONNECTIONS = 4;

    /** Network seed nodes */
    private static final String[] dnsSeeds = new String[] {
            "seed.bitcoin.sipa.be",         // Pieter Wuille
            "dnsseed.bluematt.me",          // Matt Corallo
            "dnsseed.bitcoin.dashjr.org",   // Luke Dashjr
            "seed.bitcoinstats.com"         // bitcoinstats.com
    };

    /** Network handler thread */
    private Thread handlerThread;

    /** Network timer */
    private Timer timer;

    /** Number of outbound connections */
    private int outboundCount;

    /** Network selector */
    private Selector networkSelector;

    /** Connections list */
    private List<Peer> connections = new LinkedList<>();

    /** Connection listeners */
    private List<ConnectionListener> listeners = new LinkedList<>();

    /** Time of Last peer database update */
    private long lastPeerUpdateTime;

    /** Time of last outbound connection attempt */
    private long lastOutboundConnectTime;

    /** Last connection check time */
    private long lastConnectionCheckTime;

    /** Network shutdown */
    private boolean networkShutdown = false;

    /** Static connections */
    private boolean staticConnections = false;

    /** Get blocks message sent */
    private boolean getBlocksSent = false;

    /**
     * Creates the network handler
     *
     * @param       staticAddresses     Static peer address
     * @throws      IOException         Unable to initialize selector
     */
    public NetworkHandler(PeerAddress[] staticAddresses) throws IOException {
        //
        // Create the selector for listening for network events
        //
        networkSelector = Selector.open();
        //
        // Build the static peer address list
        //
        if (staticAddresses != null) {
            staticConnections = true;
            for (PeerAddress address : staticAddresses) {
                address.setStatic(true);
                Parameters.peerAddresses.add(address);
                Parameters.peerMap.put(address, address);
            }
        }
    }

    /**
     * Processes network events
     */
    @Override
    public void run() {
        log.info(String.format("Network handler started: Max connections %d", MAX_OUTBOUND_CONNECTIONS));
        lastPeerUpdateTime = System.currentTimeMillis()/1000;
        lastOutboundConnectTime = lastPeerUpdateTime;
        lastConnectionCheckTime = lastPeerUpdateTime;
        handlerThread = Thread.currentThread();
        //
        // Get the peer nodes DNS discovery if we are not using static connections
        //
        if (!staticConnections)
            dnsDiscovery();
        //
        // Create the initial outbound connections
        //
        while (!networkShutdown && outboundCount < MAX_OUTBOUND_CONNECTIONS/2 &&
                                   connections.size() < Parameters.peerAddresses.size())
            if (!connectOutbound())
                break;
        //
        // Create a timer to wake us up every 2 minutes
        //
        timer = new Timer();
        timer.schedule(new TimerTask() {
            public void run() {
                wakeup();
            }
        }, 2*60*1000, 2*60*1000);
        //
        // Process network events until shutdown() is called
        //
        try {
            while (!networkShutdown)
                processEvents();
        } catch (Exception exc) {
            log.error("Exception while processing network events", exc);
        }
        //
        // Stopping
        //
        timer.cancel();
        log.info("Network handler stopped");
    }

    /**
     * Process network events
     */
    private void processEvents() {
        int count;
        try {
            //
            // Process selectable events
            //
            // Note that you need to remove the key from the selected key
            // set.  Otherwise, the selector will return immediately since
            // it thinks there are still unprocessed events.  Also, accessing
            // a key after the channel is closed will cause an exception to be
            // thrown, so it is best to test for just one event at a time.
            //
            count = networkSelector.select();
            if (count > 0 && !networkShutdown) {
                Set<SelectionKey> selectedKeys = networkSelector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
                while (keyIterator.hasNext() && !networkShutdown) {
                    SelectionKey key = keyIterator.next();
                    SelectableChannel channel = key.channel();
                    if (channel.isOpen()) {
                        if (key.isConnectable())
                            processConnect(key);
                        else if (key.isReadable())
                            processRead(key);
                        else if (key.isWritable())
                            processWrite(key);
                    }
                    keyIterator.remove();
                }
            }

            if (!networkShutdown) {
                //
                // Process completed messages
                //
                if (!Parameters.completedMessages.isEmpty())
                    processCompletedMessages();
                //
                // Process peer requests
                //
                if (!Parameters.pendingRequests.isEmpty() || !Parameters.processedRequests.isEmpty())
                    processRequests();
                //
                // Remove peer addresses that we haven't seen in the last 30 minutes
                //
                long currentTime = System.currentTimeMillis()/1000;
                if (currentTime > lastPeerUpdateTime + (30*60)) {
                    synchronized(Parameters.lock) {
                        Iterator<PeerAddress> iterator = Parameters.peerAddresses.iterator();
                        while (iterator.hasNext()) {
                            PeerAddress address = iterator.next();
                            if (address.isStatic())
                                continue;
                            long timestamp = address.getTimeStamp();
                            if (timestamp < lastPeerUpdateTime) {
                                Parameters.peerMap.remove(address);
                                iterator.remove();
                            }
                        }
                    }
                    lastPeerUpdateTime = currentTime;
                }
                //
                // Check for inactive peer connections every 5 minutes
                //
                // Close the connection if the peer hasn't completed the version handshake within 5 minutes.
                // Send a 'ping' message if the peer has been inactive for 5 minutes.
                // Close the connection if the peer has been inactive for 10 minutes.
                //
                if (currentTime > lastConnectionCheckTime+5*60) {
                    lastConnectionCheckTime = currentTime;
                    List<Peer> inactiveList = new LinkedList<>();
                    for (Peer chkPeer : connections) {
                        PeerAddress chkAddress = chkPeer.getAddress();
                        if (chkAddress.getTimeStamp() < currentTime-10*60) {
                            inactiveList.add(chkPeer);
                        } else if (chkAddress.getTimeStamp() < currentTime-5*60) {
                            if (chkPeer.getVersionCount() < 2) {
                                inactiveList.add(chkPeer);
                            } else if (!chkPeer.wasPingSent()) {
                                chkPeer.setPing(true);
                                Message chkMsg = PingMessage.buildPingMessage(chkPeer);
                                synchronized(Parameters.lock) {
                                    chkPeer.getOutputList().add(chkMsg);
                                    SelectionKey chkKey = chkPeer.getKey();
                                    chkKey.interestOps(chkKey.interestOps() | SelectionKey.OP_WRITE);
                                    log.info(String.format("'ping' message sent to %s", chkAddress.toString()));
                                }
                            }
                        }
                    }
                    //
                    // Close inactive connections and remove the peer from the address list
                    //
                    for (Peer chkPeer : inactiveList) {
                        log.info(String.format("Closing connection due to inactivity: %s",
                                               chkPeer.getAddress().toString()));
                        closeConnection(chkPeer);
                        synchronized(Parameters.lock) {
                            PeerAddress chkAddress = chkPeer.getAddress();
                            Parameters.peerMap.remove(chkAddress);
                            Parameters.peerAddresses.remove(chkAddress);
                        }
                    }
                }
                //
                // Create a new outbound connection if we have less than the
                // maximum number and we haven't tried for 60 seconds
                //
                if (currentTime > lastOutboundConnectTime+60) {
                    lastOutboundConnectTime = currentTime;
                    if (outboundCount < MAX_OUTBOUND_CONNECTIONS &&
                                        connections.size() < Parameters.peerAddresses.size())
                        connectOutbound();
                }
            }
        } catch (ClosedChannelException exc) {
            log.error("Network channel closed unexpectedly", exc);
        } catch (ClosedSelectorException exc) {
            log.error("Network selector closed unexpectedly", exc);
            networkShutdown = true;
        } catch (IOException exc) {
            log.error("I/O error while processing selection event", exc);
        }
    }

    /**
     * Add a connection listener
     *
     * @param       listener        Connection listener
     */
    public void addListener(ConnectionListener listener) {
        //
        // Add to the listeners list
        //
        synchronized(Parameters.lock) {
            listeners.add(listener);
        }
        //
        // Notify the new listener of existing connections
        //
        for (Peer peer : connections) {
            if (peer.getVersionCount() > 2)
                listener.connectionStarted(peer);
        }
    }

    /**
     * Wakes up the network handler
     */
    public void wakeup() {
        if (Thread.currentThread() != handlerThread)
            networkSelector.wakeup();
    }

    /**
     * Shutdowns the network handler
     */
    public void shutdown() {
        networkShutdown = true;
        wakeup();
    }

    /**
     * Sends a message to a connected peer
     *
     * @param       msg             The message to be sent
     */
    public void sendMessage(Message msg) {
        Peer peer = msg.getPeer();
        SelectionKey key = peer.getKey();
        PeerAddress address = peer.getAddress();
        synchronized(Parameters.lock) {
            if (address.isConnected()) {
                peer.getOutputList().add(msg);
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }
        }
        //
        // Wakeup the network handler to send the message
        //
        wakeup();
    }

    /**
     * Broadcasts a message to all connected peers
     *
     * Block notifications will be sent to peers that are providing network services.
     * Transaction notifications will be sent to peers that have requested transaction relays.
     * Alert notifications will be sent to all peers and all alert listeners will be notified.
     */
    public void broadcastMessage(Message msg) {
        //
        // Send the message to each connected peer
        //
        synchronized(Parameters.lock) {
            for (Peer relayPeer : connections) {
                if (relayPeer.getVersionCount() < 2)
                    continue;
                relayPeer.getOutputList().add(msg.clone(relayPeer));
                SelectionKey relayKey = relayPeer.getKey();
                relayKey.interestOps(relayKey.interestOps() | SelectionKey.OP_WRITE);
            }
        }
        //
        // Wakeup the network listener to send the broadcast messages
        //
        wakeup();
    }

    /**
     * Creates a new outbound connection
     *
     * A random peer is selected from the peer address list and a socket channel is opened.
     * The channel is placed in non-blocking mode and the connection is initiated.  An OP_CONNECT
     * selection event will be generated when the connection has been established or has failed.
     *
     * @return      TRUE if a connection was established
     */
    private boolean connectOutbound() {
        //
        // Pick an IPv4 peer at random (no sense picking an IPv6 peer at this point since our
        // internet provider doesn't support residential IPv6 addresses)
        //
        PeerAddress address;
        boolean addressFound = true;
        synchronized(Parameters.lock) {
            int index = (int)((double)Parameters.peerAddresses.size() * Math.random());
            address = Parameters.peerAddresses.get(index);
            if (!(address.getAddress() instanceof Inet4Address) ||
                                address.isConnected() || (staticConnections && !address.isStatic())) {
                addressFound = false;
                for (int i=index+1; i<Parameters.peerAddresses.size(); i++) {
                    address = Parameters.peerAddresses.get(i);
                    if ((address.getAddress() instanceof Inet4Address) &&
                                !address.isConnected() && (!staticConnections || address.isStatic())) {
                        addressFound = true;
                        break;
                    }
                }
            }
            if (!addressFound) {
                for (int i=0; i<index; i++) {
                    address = Parameters.peerAddresses.get(i);
                    if ((address.getAddress() instanceof Inet4Address) &&
                                !address.isConnected() && (!staticConnections || address.isStatic())) {
                        addressFound = true;
                        break;
                    }
                }
            }
        }
        if (!addressFound)
            return false;
        //
        // Create a socket channel for the connection and open the connection
        //
        try {
            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            channel.bind(null);
            SelectionKey key = channel.register(networkSelector, SelectionKey.OP_CONNECT);
            Peer peer = new Peer(address, channel, key);
            key.attach(peer);
            peer.setConnected(true);
            address.setConnected(true);
            channel.connect(address.toSocketAddress());
            outboundCount++;
            synchronized(Parameters.lock) {
                connections.add(peer);
            }
        } catch (IOException exc) {
            log.error(String.format("Unable to open connection to %s", address.toString()), exc);
            networkShutdown = true;
        }
        return true;
    }

    /**
     * Processes an OP_CONNECT selection event
     *
     * We will finish the connection and send a Version message to the remote peer
     *
     * @param       key             The channel selection key
     */
    private void processConnect(SelectionKey key) {
        Peer peer = (Peer)key.attachment();
        PeerAddress address = peer.getAddress();
        SocketChannel channel = peer.getChannel();
        try {
            channel.finishConnect();
            log.info(String.format("Connection established to %s", address.toString()));
            Message msg = VersionMessage.buildVersionMessage(peer, Parameters.wallet.getChainHeight());
            synchronized(Parameters.lock) {
                peer.getOutputList().add(msg);
                key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            }
            log.info(String.format("Sent 'version' message to %s", address.toString()));
        } catch (ConnectException exc) {
            log.info(exc.getLocalizedMessage());
            closeConnection(peer);
            if (!address.isStatic()) {
                synchronized(Parameters.lock) {
                    if (Parameters.peerMap.get(address) != null) {
                        Parameters.peerAddresses.remove(address);
                        Parameters.peerMap.remove(address);
                    }
                }
            }
        } catch (IOException exc) {
            log.error(String.format("Connection failed to %s", address.toString()), exc);
            closeConnection(peer);
        }
    }

    /**
     * Processes an OP_READ selection event
     *
     * @param       key             The channel selection key
     */
    private void processRead(SelectionKey key) {
        Peer peer = (Peer)key.attachment();
        PeerAddress address = peer.getAddress();
        SocketChannel channel = peer.getChannel();
        ByteBuffer buffer = peer.getInputBuffer();
        address.setTimeStamp(System.currentTimeMillis()/1000);
        try {
            int count;
            //
            // Read data until we have a complete message or no more data is available
            //
            while (true) {
                //
                // Allocate a header buffer if no read is in progress
                //
                if (buffer == null) {
                    buffer = ByteBuffer.wrap(new byte[MessageHeader.HEADER_LENGTH]);
                    peer.setInputBuffer(buffer);
                }
                //
                // Fill the input buffer
                //
                if (buffer.position() < buffer.limit()) {
                    count = channel.read(buffer);
                    if (count <= 0) {
                        if (count < 0)
                            closeConnection(peer);
                        break;
                    }
                }
                //
                // Process the message header
                //
                if (buffer.position() == buffer.limit() && buffer.limit() == MessageHeader.HEADER_LENGTH) {
                    byte[] hdrBytes = buffer.array();
                    long magic = Utils.readUint32LE(hdrBytes, 0);
                    long length = Utils.readUint32LE(hdrBytes, 16);
                    if (magic != Parameters.MAGIC_NUMBER) {
                        log.error(String.format("Message magic number %X is incorrect", magic));
                        Main.dumpData("Failing Message Header", hdrBytes);
                        closeConnection(peer);
                        break;
                    }
                    if (length > Parameters.MAX_MESSAGE_SIZE) {
                        log.error(String.format("Message length %,d is too large", length));
                        closeConnection(peer);
                        break;
                    }
                    if (length > 0) {
                        byte[] msgBytes = new byte[MessageHeader.HEADER_LENGTH+(int)length];
                        System.arraycopy(hdrBytes, 0, msgBytes, 0, MessageHeader.HEADER_LENGTH);
                        buffer = ByteBuffer.wrap(msgBytes);
                        buffer.position(MessageHeader.HEADER_LENGTH);
                        peer.setInputBuffer(buffer);
                    }
                }
                //
                // Queue the message for a message handler
                //
                // We will disable read operations for this peer if it has too many
                // pending messages.  Read operations will be re-enabled once
                // all of the messages have been processed.  We do this to keep
                // one node from flooding us with requests.
                //
                if (buffer.position() == buffer.limit()) {
                    peer.setInputBuffer(null);
                    buffer.position(0);
                    Message msg = new Message(buffer, peer, 0);
                    Parameters.messageQueue.put(msg);
                    break;
                }
            }
        } catch (IOException exc) {
            closeConnection(peer);
        } catch (InterruptedException exc) {
            log.warn("Interrupted while processing read request");
            networkShutdown = true;
        }
    }

    /**
     * Processes an OP_WRITE selection event
     *
     * @param       key             The channel selection key
     */
    private void processWrite(SelectionKey key) {
        Peer peer = (Peer)key.attachment();
        SocketChannel channel = peer.getChannel();
        ByteBuffer buffer = peer.getOutputBuffer();
        try {
            //
            // Write data until all pending messages have been sent or the socket buffer is full
            //
            while (true) {
                //
                // Get the next message if no write is in progress.  Disable write events
                // if there are no more messages to write.
                //
                if (buffer == null) {
                    synchronized(Parameters.lock) {
                        List<Message> outputList = peer.getOutputList();
                        if (outputList.isEmpty()) {
                            key.interestOps(key.interestOps() & (~SelectionKey.OP_WRITE));
                        } else {
                            Message msg = outputList.remove(0);
                            buffer = msg.getBuffer();
                            peer.setOutputBuffer(buffer);
                        }
                    }
                }
                //
                // Stop if all messages have been sent
                //
                if (buffer == null)
                    break;
                //
                // Write the current buffer to the channel
                //
                channel.write(buffer);
                if (buffer.position() < buffer.limit())
                    break;
                buffer = null;
                peer.setOutputBuffer(null);
            }
        } catch (IOException exc) {
            closeConnection(peer);
        }
    }


    /**
     * Closes a peer connection and discards any pending messages
     *
     * @param       peer            The peer being closed
     */
    private void closeConnection(Peer peer) {
        PeerAddress address = peer.getAddress();
        SocketChannel channel = peer.getChannel();
        try {
            //
            // Disconnect the peer
            //
            peer.setInputBuffer(null);
            peer.setOutputBuffer(null);
            peer.getOutputList().clear();
            outboundCount--;
            address.setConnected(false);
            peer.setConnected(false);
            synchronized(Parameters.lock) {
                connections.remove(peer);
            }
            //
            // Close the channel
            //
            if (channel.isOpen())
                channel.close();
            //
            // Notify listeners
            //
            if (peer.getVersionCount() > 2) {
                for (ConnectionListener listener : listeners)
                    listener.connectionEnded(peer);
            }
            log.info(String.format("Connection closed with peer %s", address.toString()));
        } catch (IOException exc) {
            log.error(String.format("Error while closing socket channel with %s", address.toString()), exc);
        }
    }

    /**
     * Processes completed messages
     */
    private void processCompletedMessages() {
        while (!Parameters.completedMessages.isEmpty()) {
            Message msg;
            synchronized(Parameters.lock) {
                msg = Parameters.completedMessages.remove(0);
            }
            Peer peer = msg.getPeer();
            PeerAddress address = peer.getAddress();
            SelectionKey key = peer.getKey();
            //
            // Nothing to do if the connection has been closed
            //
            if (!address.isConnected())
                continue;
            //
            // Close the connection if requested
            //
            if (peer.shouldDisconnect()) {
                closeConnection(peer);
                continue;
            }
            //
            // Send the response (if any)
            //
            if (msg.getBuffer() != null) {
                synchronized(Parameters.lock) {
                    peer.getOutputList().add(msg);
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
            }
            //
            // Sent initial setup messages if we have successfully exchanged 'version' messages
            //
            if (peer.getVersionCount() == 2) {
                peer.incVersionCount();
                log.info(String.format("Connection handshake completed with %s", address.toString()));
                Parameters.networkChainHeight = Math.max(Parameters.networkChainHeight, peer.getHeight());
                //
                // Send a 'getaddr' message to exchange peer address lists.
                // Do not do this if we are using static connections since we don't need
                // to know peer addresses.
                //
                if (!staticConnections) {
                    Message addrMsg = GetAddressMessage.buildGetAddressMessage(peer);
                    synchronized(Parameters.lock) {
                        peer.getOutputList().add(addrMsg);
                        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    }
                    log.info(String.format("'getaddr' message sent to %s", address.toString()));
                }
                //
                // Load our bloom filter
                //
                Message filterMsg = FilterLoadMessage.buildFilterLoadMessage(peer, Parameters.bloomFilter);
                synchronized(Parameters.lock) {
                    peer.getOutputList().add(filterMsg);
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
                log.info(String.format("'filterload' message sent to %s", address.toString()));
                //
                // Send a 'getblocks' message if we are down-level and haven't sent one yet
                //
                if (!getBlocksSent && Parameters.wallet.getChainHeight() < peer.getHeight()) {
                    Message blocksMsg = GetBlocksMessage.buildGetBlocksMessage(peer);
                    synchronized(Parameters.lock) {
                        peer.getOutputList().add(blocksMsg);
                        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    }
                    log.info(String.format("'getblocks' message sent to %s", address.toString()));
                    getBlocksSent = true;
                }
                //
                // Notify listeners
                //
                for (ConnectionListener listener : listeners)
                    listener.connectionStarted(peer);
            }
        }
    }

    /**
     * Process peer requests
     */
    private void processRequests() {
        long currentTime = System.currentTimeMillis()/1000;
        PeerRequest request = null;
        Peer peer = null;
        //
        // Check for request timeouts (we will wait 30 seconds for a response)
        //
        synchronized(Parameters.lock) {
            while (!Parameters.processedRequests.isEmpty()) {
                request = Parameters.processedRequests.get(0);
                if (request.getTimeStamp() >= currentTime-30 || request.isProcessing())
                    break;
                //
                // Move the request back to the pending queue
                //
                Parameters.processedRequests.remove(0);
                Parameters.pendingRequests.add(request);
            }
        }
        //
        // Send pending requests
        //
        while (!Parameters.pendingRequests.isEmpty()) {
            synchronized(Parameters.lock) {
                request = Parameters.pendingRequests.remove(0);
                Parameters.processedRequests.add(request);
            }
            //
            // Send the request to the origin peer unless we already tried or the peer is
            // no longer connected
            //
            peer = request.getOrigin();
            if (peer != null && (request.wasContacted(peer) || !peer.isConnected()))
                peer = null;
            //
            // Select a peer to process the request.  The peer must provide network
            // services and must not have been contacted for this request.
            //
            if (peer == null) {
                int index = (int)(((double)connections.size())*Math.random());
                for (int i=index; i<connections.size(); i++) {
                    Peer chkPeer = connections.get(i);
                    if ((chkPeer.getServices()&Parameters.NODE_NETWORK)!=0 &&
                                                !request.wasContacted(chkPeer) && chkPeer.isConnected()) {
                        peer = chkPeer;
                        break;
                    }
                }
                if (peer == null) {
                    for (int i=0; i<index; i++) {
                        Peer chkPeer = connections.get(i);
                        if ((chkPeer.getServices()&Parameters.NODE_NETWORK)!=0 &&
                                                !request.wasContacted(chkPeer) && chkPeer.isConnected()) {
                            peer = chkPeer;
                            break;
                        }
                    }
                }
            }
            //
            // Discard the request if all of the available peers have been contacted.  We will
            // increment the banscore for the origin peer since he is broadcasting inventory
            // that he doesn't have.
            //
            if (peer == null) {
                Peer originPeer = request.getOrigin();
                synchronized(Parameters.lock) {
                    Parameters.processedRequests.remove(request);
                    if (originPeer != null) {
                        int banScore = originPeer.getBanScore() + 2;
                        originPeer.setBanScore(banScore);
                        if (banScore >= Parameters.MAX_BAN_SCORE)
                            originPeer.setDisconnect(true);
                    }
                }
                String originAddress = (originPeer!=null ? originPeer.getAddress().toString() : "local");
                log.warn(String.format("Purging unavailable %s request initiated by %s\n  %s",
                                       (request.getType()==Parameters.INV_BLOCK?"block":"transaction"),
                                       originAddress, request.getHash().toString()));
                continue;
            }
            //
            // Send the request to the peer
            //
            request.addPeer(peer);
            request.setTimeStamp(currentTime);
            List<Sha256Hash> hashList = new ArrayList<>(1);
            hashList.add(request.getHash());
            Message msg = GetDataMessage.buildGetDataMessage(peer, request.getType(), hashList);
            synchronized(Parameters.lock) {
                peer.getOutputList().add(msg);
                SelectionKey key = peer.getKey();
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    /**
     * Performs DNS lookups to get the initial peer list
     */
    private void dnsDiscovery() {
        //
        // Process each seed node and add the node addresses to our peer list.  We have
        // to assume that any node in the DNS seed list is a network node.
        //
        for (String host : dnsSeeds) {
            PeerAddress peerAddress = null;
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress address : addresses) {
                    peerAddress = new PeerAddress(address, Parameters.DEFAULT_PORT);
                    peerAddress.setServices(Parameters.NODE_NETWORK);
                    if (Parameters.peerMap.get(peerAddress) == null) {
                        Parameters.peerAddresses.add(peerAddress);
                        Parameters.peerMap.put(peerAddress, peerAddress);
                    }
                }
            } catch (UnknownHostException exc) {
                log.warn(String.format("DNS host %s not found", host), exc);
            }
        }
    }
}
