/**
 *
 * Copyright 2012-2014 Florian Schmaus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.smackx.ping;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jivesoftware.smack.AbstractConnectionListener;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.SmackException.NoResponseException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.ConnectionCreationListener;
import org.jivesoftware.smack.Manager;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPConnectionRegistry;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.filter.AndFilter;
import org.jivesoftware.smack.filter.IQTypeFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.ping.packet.Ping;

/**
 * Implements the XMPP Ping as defined by XEP-0199. The XMPP Ping protocol allows one entity to
 * ping any other entity by simply sending a ping to the appropriate JID. PingManger also
 * periodically sends XMPP pings to the server every 30 minutes to avoid NAT timeouts and to test
 * the connection status.
 * 
 * @author Florian Schmaus
 * @see <a href="http://www.xmpp.org/extensions/xep-0199.html">XEP-0199:XMPP Ping</a>
 */
public class PingManager extends Manager {
    private static final Logger LOGGER = Logger.getLogger(PingManager.class.getName());

    private static final Map<XMPPConnection, PingManager> INSTANCES = Collections
            .synchronizedMap(new WeakHashMap<XMPPConnection, PingManager>());

    private static final PacketFilter PING_PACKET_FILTER = new AndFilter(
                    new PacketTypeFilter(Ping.class), IQTypeFilter.GET);

    static {
        XMPPConnectionRegistry.addConnectionCreationListener(new ConnectionCreationListener() {
            public void connectionCreated(XMPPConnection connection) {
                getInstanceFor(connection);
            }
        });
    }

    /**
     * Retrieves a {@link PingManager} for the specified {@link XMPPConnection}, creating one if it doesn't already
     * exist.
     * 
     * @param connection
     * The connection the manager is attached to.
     * @return The new or existing manager.
     */
    public synchronized static PingManager getInstanceFor(XMPPConnection connection) {
        PingManager pingManager = INSTANCES.get(connection);
        if (pingManager == null) {
            pingManager = new PingManager(connection);
        }
        return pingManager;
    }

    private static int defaultPingInterval = 60 * 30;

    /**
     * Set the default ping interval which will be used for new connections.
     *
     * @param interval the interval in seconds
     */
    public static void setDefaultPingInterval(int interval) {
        defaultPingInterval = interval;
    }

    private final Set<PingFailedListener> pingFailedListeners = Collections
                    .synchronizedSet(new HashSet<PingFailedListener>());

    private final ScheduledExecutorService executorService;

    private static class PingExecutorThreadFactory implements ThreadFactory {
        private final int connectionCounterValue;

        public PingExecutorThreadFactory(int connectionCounterValue) {
            this.connectionCounterValue = connectionCounterValue;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Smack Scheduled Ping Executor Service ("
                            + connectionCounterValue + ")");
            thread.setDaemon(true);
            return thread;
        }

    }
    /**
     * The interval in seconds between pings are send to the users server.
     */
    private int pingInterval = defaultPingInterval;

    private ScheduledFuture<?> nextAutomaticPing;

    private PingManager(XMPPConnection connection) {
        super(connection);
        executorService = new ScheduledThreadPoolExecutor(1,
                        new PingExecutorThreadFactory(connection.getConnectionCounter()));
        ServiceDiscoveryManager sdm = ServiceDiscoveryManager.getInstanceFor(connection);
        sdm.addFeature(Ping.NAMESPACE);
        INSTANCES.put(connection, this);

        connection.addPacketListener(new PacketListener() {
            // Send a Pong for every Ping
            @Override
            public void processPacket(Packet packet) throws NotConnectedException {
                Ping ping = (Ping) packet;
                connection().sendPacket(ping.getPong());
            }
        }, PING_PACKET_FILTER);
        connection.addConnectionListener(new AbstractConnectionListener() {
            @Override
            public void authenticated(XMPPConnection connection) {
                maybeSchedulePingServerTask();
            }
            @Override
            public void connectionClosed() {
                maybeStopPingServerTask();
            }
            @Override
            public void connectionClosedOnError(Exception arg0) {
                maybeStopPingServerTask();
            }
        });
        maybeSchedulePingServerTask();
    }

    /**
     * Pings the given jid. This method will return false if an error occurs.  The exception 
     * to this, is a server ping, which will always return true if the server is reachable, 
     * event if there is an error on the ping itself (i.e. ping not supported).
     * <p>
     * Use {@link #isPingSupported(String)} to determine if XMPP Ping is supported 
     * by the entity.
     * 
     * @param jid The id of the entity the ping is being sent to
     * @param pingTimeout The time to wait for a reply in milliseconds
     * @return true if a reply was received from the entity, false otherwise.
     * @throws NoResponseException if there was no response from the jid.
     * @throws NotConnectedException 
     */
    public boolean ping(String jid, long pingTimeout) throws NotConnectedException, NoResponseException {
        final XMPPConnection connection = connection();
        // Packet collector for IQs needs an connection that was at least authenticated once,
        // otherwise the client JID will be null causing an NPE
        if (!connection.isAuthenticated()) {
            throw new NotConnectedException();
        }
        Ping ping = new Ping(jid);
        try {
            connection.createPacketCollectorAndSend(ping).nextResultOrThrow(pingTimeout);
        }
        catch (XMPPException exc) {
            return jid.equals(connection.getServiceName());
        }
        return true;
    }

    /**
     * Same as calling {@link #ping(String, long)} with the defaultpacket reply 
     * timeout.
     * 
     * @param jid The id of the entity the ping is being sent to
     * @return true if a reply was received from the entity, false otherwise.
     * @throws NotConnectedException
     * @throws NoResponseException if there was no response from the jid.
     */
    public boolean ping(String jid) throws NotConnectedException, NoResponseException {
        return ping(jid, connection().getPacketReplyTimeout());
    }

    /**
     * Query the specified entity to see if it supports the Ping protocol (XEP-0199)
     * 
     * @param jid The id of the entity the query is being sent to
     * @return true if it supports ping, false otherwise.
     * @throws XMPPErrorException An XMPP related error occurred during the request 
     * @throws NoResponseException if there was no response from the jid.
     * @throws NotConnectedException 
     */
    public boolean isPingSupported(String jid) throws NoResponseException, XMPPErrorException, NotConnectedException  {
        return ServiceDiscoveryManager.getInstanceFor(connection()).supportsFeature(jid, Ping.NAMESPACE);
    }

    /**
     * Pings the server. This method will return true if the server is reachable.  It
     * is the equivalent of calling <code>ping</code> with the XMPP domain.
     * <p>
     * Unlike the {@link #ping(String)} case, this method will return true even if 
     * {@link #isPingSupported(String)} is false.
     * 
     * @return true if a reply was received from the server, false otherwise.
     * @throws NotConnectedException
     */
    public boolean pingMyServer() throws NotConnectedException {
        return pingMyServer(true);
    }

    /**
     * Pings the server. This method will return true if the server is reachable.  It
     * is the equivalent of calling <code>ping</code> with the XMPP domain.
     * <p>
     * Unlike the {@link #ping(String)} case, this method will return true even if
     * {@link #isPingSupported(String)} is false.
     *
     * @param notifyListeners Notify the PingFailedListener in case of error if true
     * @return true if the user's server could be pinged.
     * @throws NotConnectedException
     */
    public boolean pingMyServer(boolean notifyListeners) throws NotConnectedException {
        return pingMyServer(notifyListeners, connection().getPacketReplyTimeout());
    }

    /**
     * Pings the server. This method will return true if the server is reachable.  It
     * is the equivalent of calling <code>ping</code> with the XMPP domain.
     * <p>
     * Unlike the {@link #ping(String)} case, this method will return true even if
     * {@link #isPingSupported(String)} is false.
     *
     * @param notifyListeners Notify the PingFailedListener in case of error if true
     * @param pingTimeout The time to wait for a reply in milliseconds
     * @return true if the user's server could be pinged.
     * @throws NotConnectedException
     */
    public boolean pingMyServer(boolean notifyListeners, long pingTimeout) throws NotConnectedException {
        boolean res;
        try {
            res = ping(connection().getServiceName(), pingTimeout);
        }
        catch (NoResponseException e) {
            res = false;
        }
        if (!res && notifyListeners) {
            for (PingFailedListener l : pingFailedListeners)
                l.pingFailed();
        }
        return res;
    }

    /**
     * Set the interval in seconds between a automated server ping is send. A negative value disables automatic server
     * pings. All settings take effect immediately. If there is an active scheduled server ping it will be canceled and,
     * if <code>pingInterval</code> is positive, a new one will be scheduled in pingInterval seconds.
     * <p>
     * If the ping fails after 3 attempts waiting the connections reply timeout for an answer, then the ping failed
     * listeners will be invoked.
     * </p>
     *
     * @param pingInterval the interval in seconds between the automated server pings
     */
    public void setPingInterval(int pingInterval) {
        this.pingInterval = pingInterval;
        maybeSchedulePingServerTask();
    }

    /**
     * Get the current ping interval.
     *
     * @return the interval between pings in seconds
     */
    public int getPingInterval() {
        return pingInterval;
    }

    /**
     * Register a new PingFailedListener
     *
     * @param listener the listener to invoke
     */
    public void registerPingFailedListener(PingFailedListener listener) {
        pingFailedListeners.add(listener);
    }

    /**
     * Unregister a PingFailedListener
     *
     * @param listener the listener to remove
     */
    public void unregisterPingFailedListener(PingFailedListener listener) {
        pingFailedListeners.remove(listener);
    }

    private void maybeSchedulePingServerTask() {
        maybeSchedulePingServerTask(0);
    }

    /**
     * Cancels any existing periodic ping task if there is one and schedules a new ping task if
     * pingInterval is greater then zero.
     *
     * @param delta the delta to the last received stanza in seconds
     */
    private synchronized void maybeSchedulePingServerTask(int delta) {
        maybeStopPingServerTask();
        if (pingInterval > 0) {
            int nextPingIn = pingInterval - delta;
            LOGGER.fine("Scheduling ServerPingTask in " + nextPingIn + " seconds (pingInterval="
                            + pingInterval + ", delta=" + delta + ")");
            nextAutomaticPing = executorService.schedule(pingServerRunnable, nextPingIn, TimeUnit.SECONDS);
        }
    }

    private void maybeStopPingServerTask() {
        if (nextAutomaticPing != null) {
            nextAutomaticPing.cancel(true);
            nextAutomaticPing = null;
        }
    }

    /**
     * Ping the server if deemed necessary because automatic server pings are
     * enabled ({@link #setPingInterval(int)}) and the ping interval has expired.
     */
    public synchronized void pingServerIfNecessary() {
        final int DELTA = 1000; // 1 seconds
        final int TRIES = 3; // 3 tries
        final XMPPConnection connection = connection();
        if (connection == null) {
            // connection has been collected by GC
            // which means we can stop the thread by breaking the loop
            return;
        }
        if (pingInterval <= 0) {
            // Ping has been disabled
            return;
        }
        long lastStanzaReceived = connection.getLastStanzaReceived();
        if (lastStanzaReceived > 0) {
            long now = System.currentTimeMillis();
            // Delta since the last stanza was received
            int deltaInSeconds = (int)  ((now - lastStanzaReceived) / 1000);
            // If the delta is small then the ping interval, then we can defer the ping
            if (deltaInSeconds < pingInterval) {
                maybeSchedulePingServerTask(deltaInSeconds);
                return;
            }
        }
        if (connection.isAuthenticated()) {
            boolean res = false;

            for (int i = 0; i < TRIES; i++) {
                if (i != 0) {
                    try {
                        Thread.sleep(DELTA);
                    } catch (InterruptedException e) {
                        // We received an interrupt
                        // This only happens if we should stop pinging
                        return;
                    }
                }
                try {
                    res = pingMyServer(false);
                }
                catch (SmackException e) {
                    LOGGER.log(Level.WARNING, "SmackError while pinging server", e);
                    res = false;
                }
                // stop when we receive a pong back
                if (res) {
                    break;
                }
            }
            if (!res) {
                for (PingFailedListener l : pingFailedListeners) {
                    l.pingFailed();
                }
            } else {
                // Ping was successful, wind-up the periodic task again
                maybeSchedulePingServerTask();
            }
        } else {
            LOGGER.warning("XMPPConnection was not authenticated");
        }
    }

    private final Runnable pingServerRunnable = new Runnable() {
        public void run() {
            LOGGER.fine("ServerPingTask run()");
            pingServerIfNecessary();
        }
    };

    @Override
    protected void finalize() throws Throwable {
        try {
            executorService.shutdown();
        } finally {
            super.finalize();
        }
    }
}
