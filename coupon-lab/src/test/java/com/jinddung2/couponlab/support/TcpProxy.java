package com.jinddung2.couponlab.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A TCP forwarder in front of a container port, so a test can take the database away and give it back on the same address
 * (a restarted container would get a new mapped port). down() closes the listening socket and every forwarded connection
 * (clients see resets, new connections are refused); up() listens again on the same port.
 */
public final class TcpProxy implements AutoCloseable {

    private final String targetHost;
    private final int targetPort;
    private final int port;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket server;

    public TcpProxy(String targetHost, int targetPort) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            this.port = probe.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        up();
    }

    public int port() {
        return port;
    }

    public boolean forwardsTo(String host, int targetPort) {
        return this.targetHost.equals(host) && this.targetPort == targetPort;
    }

    public synchronized void up() {
        if (server != null) {
            return;
        }
        try {
            ServerSocket listening = new ServerSocket();
            listening.setReuseAddress(true);
            listening.bind(new InetSocketAddress("127.0.0.1", port));
            server = listening;
            Thread acceptor = new Thread(() -> accept(listening), "tcp-proxy-accept-" + port);
            acceptor.setDaemon(true);
            acceptor.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void down() {
        ServerSocket listening = server;
        server = null;
        closeQuietly(listening);
        sockets.forEach(TcpProxy::closeQuietly);
        sockets.clear();
    }

    @Override
    public void close() {
        down();
    }

    private void accept(ServerSocket listening) {
        while (!listening.isClosed()) {
            try {
                Socket client = listening.accept();
                Socket target = new Socket(targetHost, targetPort);
                sockets.add(client);
                sockets.add(target);
                pump(client, target);
                pump(target, client);
            } catch (IOException e) {
                return;
            }
        }
    }

    private void pump(Socket from, Socket to) {
        Thread thread = new Thread(() -> {
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // a closed side ends the pump
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }, "tcp-proxy-pump-" + port);
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // nothing to do
        }
    }
}
