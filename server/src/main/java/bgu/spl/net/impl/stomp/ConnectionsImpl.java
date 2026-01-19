
package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.ConnectionHandler;
import bgu.spl.net.srv.Connections;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ConnectionsImpl<T> implements Connections<T> {

    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> activeConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Integer>> channelSubscribers = new ConcurrentHashMap<>();

    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = activeConnections.get(connectionId);
        if (handler != null) {
            handler.send(msg);
            return true;
        }
        return false;
    }

    @Override
    public void send(String channel, T msg) {
        ConcurrentLinkedQueue<Integer> subs = channelSubscribers.get(channel);
        if (subs != null) {
            for (Integer id : subs) {
                send(id, msg);
            }
        }
    }

    @Override
    public void disconnect(int connectionId) {
        activeConnections.remove(connectionId);

        // remove from all channels to avoid stale subscribers
        for (ConcurrentLinkedQueue<Integer> q : channelSubscribers.values()) {
            q.remove(connectionId);
        }
    }

    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        activeConnections.put(connectionId, handler);
    }

    public void subscribe(String channel, int connectionId) {
        channelSubscribers
                .computeIfAbsent(channel, k -> new ConcurrentLinkedQueue<>())
                .add(connectionId);
    }

    public void unsubscribe(String channel, int connectionId) {
        ConcurrentLinkedQueue<Integer> subscribers = channelSubscribers.get(channel);
        if (subscribers != null) {
            subscribers.remove(connectionId);
        }
    }

    public ConcurrentLinkedQueue<Integer> getSubscribers(String channel) {
        return channelSubscribers.get(channel);
    }
}
