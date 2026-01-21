package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.data.User;
import bgu.spl.net.srv.Connections;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    private int connectionId;
    private Connections<String> connections;
    private boolean shouldTerminate = false;

    private static final AtomicInteger messageIdCounter = new AtomicInteger(0);

    private static final ConcurrentHashMap<String, User> usersMap = new ConcurrentHashMap<>();
    private User currentUser = null;

    private String lastFrame = "";

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public String process(String message) {
        if (message == null) return null;

        lastFrame = message; 

        String[] lines = message.split("\n");
        if (lines.length == 0) return null;

        String command = lines[0].trim();
        Map<String, String> headers = parseHeaders(lines);
        String body = extractBody(message);

        if (currentUser == null && !command.equals("CONNECT")) {
            sendError(
                    "Not logged in",
                    headers.get("receipt"),
                    "You must CONNECT first",
                    lastFrame
            );
            return null;
        }

        switch (command) {
            case "CONNECT":
                if (currentUser != null && currentUser.isLoggedIn()) {
                    sendError("malformed frame received",
                            headers.get("receipt"),
                            "Already connected. CONNECT is allowed only once per connection.",
                            lastFrame);
                    return null;
                }
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
                sendError(
                        "malformed frame received",
                        headers.get("receipt"),
                        "Command not recognized",
                        lastFrame
                );
                break;
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
        String receipt = headers.get("receipt");
        
        if (login == null || passcode == null) {
            sendError(
                    "malformed frame received",
                    receipt,
                    "Missing login or passcode header",
                    lastFrame
            );
            return;
        }

        synchronized (usersMap) {
            User user = usersMap.get(login);

            if (user == null) {
                user = new User(connectionId, login, passcode);
                usersMap.put(login, user);
            } else {
                if (!user.password.equals(passcode)) {
                    sendError(
                            "malformed frame received",
                            receipt,
                            "Wrong password",
                            lastFrame
                    );
                    return;
                }
                if (user.isLoggedIn()) {
                    sendError(
                            "malformed frame received",
                            receipt,
                            "User already logged in",
                            lastFrame
                    );
                    return;
                }
            }

            user.setConnectionId(connectionId);
            user.login();
            currentUser = user;

            sendConnected();
            if (receipt != null) sendReceipt(receipt);
        }
    }

    private void handleSubscribe(Map<String, String> headers) {
        String topic = headers.get("destination");
        String subId = headers.get("id");
        String receipt = headers.get("receipt");

        if (topic == null || subId == null) {
            sendError(
                    "malformed frame received",
                    receipt,
                    "Missing destination or id header",
                    lastFrame
            );
            return;
        }

        currentUser.addSubscription(topic, subId);

        if (connections instanceof ConnectionsImpl) {
            ((ConnectionsImpl<String>) connections).subscribe(topic, connectionId);
        } else {
            sendError(
                    "malformed frame received",
                    receipt,
                    "Server connections implementation mismatch",
                    lastFrame
            );
            return;
        }

        if (receipt != null) sendReceipt(receipt);
    }

    private void handleUnsubscribe(Map<String, String> headers) {
        String subId = headers.get("id");
        String receipt = headers.get("receipt");

        if (subId == null) {
            sendError(
                    "malformed frame received",
                    receipt,
                    "Missing id header",
                    lastFrame
            );
            return;
        }

        String topic = currentUser.getTopic(subId);
        if (topic == null) {
            sendError(
                    "malformed frame received",
                    receipt,
                    "Unknown subscription id",
                    lastFrame
            );
            return;
        }

        currentUser.removeSubscription(subId);

        if (connections instanceof ConnectionsImpl) {
            ((ConnectionsImpl<String>) connections).unsubscribe(topic, connectionId);
        }

        if (receipt != null) sendReceipt(receipt);
    }

    private void handleSend(Map<String, String> headers, String body) {
        String topic = headers.get("destination");
        String receipt = headers.get("receipt");

        if (topic == null) {
            sendError(
                    "malformed frame received",
                    receipt,
                    "Did not contain a destination header, which is REQUIRED for message propagation.",
                    lastFrame
            );
            return;
        }

        if (currentUser.getSubscriptionId(topic) == null) {
            sendError(
                    "Permission denied",
                    receipt,
                    "Not subscribed to topic",
                    lastFrame
            );
            return;
        }

        if (!(connections instanceof ConnectionsImpl)) {
            sendError(
                    "malformed frame received",
                    receipt,
                    "Server connections implementation mismatch",
                    lastFrame
            );
            return;
        }

        ConnectionsImpl<String> connImpl = (ConnectionsImpl<String>) connections;
        ConcurrentLinkedQueue<Integer> subscribers = connImpl.getSubscribers(topic);

        int msgId = messageIdCounter.incrementAndGet();
        if (subscribers != null) {
            for (Integer targetConnId : subscribers) {
                User targetUser = findUserByConnectionId(targetConnId);
                if (targetUser == null) continue;

                String targetSubId = targetUser.getSubscriptionId(topic);
                if (targetSubId == null) continue;

                String msgFrame = createMessageFrame(topic, targetSubId, body,msgId);
                connections.send(targetConnId, msgFrame);
            }
        }

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

    private String createMessageFrame(String topic, String subId, String body, int msgId) {
        return "MESSAGE\n" +
                "subscription:" + subId + "\n" +
                "message-id:" + msgId + "\n" +
                "destination:" + topic + "\n\n" +
                body;
    }

    private void sendConnected() {
        connections.send(connectionId, "CONNECTED\nversion:1.2\n\n");
    }

    private void sendReceipt(String receiptId) {
        connections.send(connectionId, "RECEIPT\nreceipt-id:" + receiptId + "\n\n");
    }

    private void sendError(String shortMsg, String receiptId, String details, String originalFrame) {
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR\n");

        if (receiptId != null) {
            sb.append("receipt-id: ").append(receiptId).append("\n");
        }

        sb.append("message: ").append(shortMsg).append("\n");
        sb.append("\n"); 

        if (originalFrame != null && !originalFrame.isEmpty()) {
            sb.append("The message:\n");
            sb.append("----\n");
            sb.append(originalFrame).append("\n");
            sb.append("----\n");
        }

        if (details != null && !details.isEmpty()) {
            sb.append(details).append("\n");
        }

        connections.send(connectionId, sb.toString());

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
