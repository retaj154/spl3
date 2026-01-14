
package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.data.User;
import bgu.spl.net.srv.Connections;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    private int connectionId;
    private Connections<String> connections;
    private boolean shouldTerminate = false;

    private static final ConcurrentHashMap<String, User> usersMap = new ConcurrentHashMap<>();
    private User currentUser = null;

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public String process(String message) {
        String[] lines = message.split("\n");
        if (lines.length == 0) return null;

        String command = lines[0].trim();
        Map<String, String> headers = parseHeaders(lines);
        String body = extractBody(message);

        if (currentUser == null && !command.equals("CONNECT")) {
            sendError("Not logged in", "You must log in first");
            return null;
        }

        switch (command) {
            case "CONNECT":
                handleConnect(headers);
                break;
            case "SEND":
                handleSend(headers, body);
                break;
            case "SUBSCRIBE":
                handleSubscribe(headers);
                break;
            case "UNSUBSCRIBE":
                handleUnsubscribe(headers);
                break;
            case "DISCONNECT":
                handleDisconnect(headers);
                break;
            default:
                sendError("Unknown Command", "Command not recognized");
        }
        return null;
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    private void handleConnect(Map<String, String> headers) {
        String login = headers.get("login");
        String passcode = headers.get("passcode");

        if (login == null || passcode == null) {
            sendError("Malformed Frame", "Missing login or passcode");
            return;
        }

        synchronized (usersMap) {
            if (!usersMap.containsKey(login)) {
                User newUser = new User(connectionId, login, passcode);
                newUser.login();
                usersMap.put(login, newUser);
                currentUser = newUser;
                sendConnected();
            } else {
                User user = usersMap.get(login);

                if (!user.password.equals(passcode)) {
                    sendError("Wrong password", "Password does not match");
                    return;
                }

                if (user.isLoggedIn()) {
                    sendError("User already logged in", "User is already logged in");
                    return;
                }

                user.setConnectionId(connectionId);
                user.login();
                currentUser = user;
                sendConnected();
            }
        }
    }

    private void handleSubscribe(Map<String, String> headers) {
        String topic = headers.get("destination");
        String subId = headers.get("id");
        String receipt = headers.get("receipt");

        if (topic == null || subId == null) {
            sendError("Malformed Frame", "Missing destination or id header");
            return;
        }

        currentUser.addSubscription(topic, subId);

        if (connections instanceof ConnectionsImpl) {
            ((ConnectionsImpl<String>) connections).subscribe(topic, connectionId);
        } else {
            sendError("Server error", "Connections implementation mismatch");
            return;
        }

        if (receipt != null) sendReceipt(receipt);
    }

    private void handleUnsubscribe(Map<String, String> headers) {
        String subId = headers.get("id");
        String receipt = headers.get("receipt");

        if (subId == null) {
            sendError("Malformed Frame", "Missing id header");
            return;
        }

        String topic = currentUser.getTopic(subId);
        if (topic != null) {
            currentUser.removeSubscription(subId);

            if (connections instanceof ConnectionsImpl) {
                ((ConnectionsImpl<String>) connections).unsubscribe(topic, connectionId);
            }

            if (receipt != null) sendReceipt(receipt);
        }
    }

    private void handleSend(Map<String, String> headers, String body) {
        String topic = headers.get("destination");
        if (topic == null) {
            sendError("Malformed Frame", "Missing destination");
            return;
        }

        // Per assignment: if not subscribed -> ERROR + disconnect
        if (currentUser.getSubscriptionId(topic) == null) {
            sendError("Permission denied", "Not subscribed to topic");
            return;
        }

        if (!(connections instanceof ConnectionsImpl)) {
            sendError("Server error", "Connections implementation mismatch");
            return;
        }

        ConnectionsImpl<String> connImpl = (ConnectionsImpl<String>) connections;
        ConcurrentLinkedQueue<Integer> subscribers = connImpl.getSubscribers(topic);

        if (subscribers != null) {
            for (Integer targetConnId : subscribers) {
                User targetUser = findUserByConnectionId(targetConnId);
                if (targetUser == null) continue;

                String targetSubId = targetUser.getSubscriptionId(topic);
                if (targetSubId == null) continue;

                String msgFrame = createMessageFrame(topic, targetSubId, body);
                connections.send(targetConnId, msgFrame);
            }
        }

        String receipt = headers.get("receipt");
        if (receipt != null) sendReceipt(receipt);
    }

    private void handleDisconnect(Map<String, String> headers) {
        String receiptId = headers.get("receipt");

        if (receiptId != null) sendReceipt(receiptId);

        if (currentUser != null) {
            currentUser.logout();
            currentUser.clearSubscriptions();
            currentUser = null;
        }

        shouldTerminate = true;
        connections.disconnect(connectionId);
    }

    private User findUserByConnectionId(int connId) {
        for (User u : usersMap.values()) {
            if (u.getConnectionId() == connId && u.isLoggedIn()) return u;
        }
        return null;
    }

    // IMPORTANT: no \u0000 here. EncoderDecoder adds it.
    private String createMessageFrame(String topic, String subId, String body) {
        return "MESSAGE\n" +
                "subscription:" + subId + "\n" +
                "message-id:" + java.util.UUID.randomUUID() + "\n" +
                "destination:" + topic + "\n\n" +
                body;
    }

    private void sendConnected() {
        connections.send(connectionId, "CONNECTED\nversion:1.2\n\n");
    }

    private void sendReceipt(String receiptId) {
        connections.send(connectionId, "RECEIPT\nreceipt-id:" + receiptId + "\n\n");
    }

    private void sendError(String message, String desc) {
        connections.send(connectionId, "ERROR\nmessage:" + message + "\n\n" + desc);
        shouldTerminate = true;
        connections.disconnect(connectionId);
    }

    private Map<String, String> parseHeaders(String[] lines) {
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) break;

            String[] parts = line.split(":", 2);
            if (parts.length == 2) {
                headers.put(parts[0].trim(), parts[1].trim());
            }
        }
        return headers;
    }

    private String extractBody(String msg) {
        int idx = msg.indexOf("\n\n");
        if (idx == -1) return "";
        return msg.substring(idx + 2);
    }
}
