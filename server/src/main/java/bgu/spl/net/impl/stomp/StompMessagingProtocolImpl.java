package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.data.Database;
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

    private static final ConcurrentHashMap<String, User> activeByName = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, User> activeByConn = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Boolean> fileUploadOnce = new ConcurrentHashMap<>();

    private final Database db = Database.getInstance();

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

        String[] lines = message.split("\n", -1);
        if (lines.length == 0) return null;

        String command = lines[0].trim();
        Map<String, String> headers = new HashMap<>();
        String body = parseHeadersAndBody(lines, headers);

        switch (command) {
            case "CONNECT":
                handleConnect(headers);
                break;
            case "SUBSCRIBE":
                handleSubscribe(headers);
                break;
            case "UNSUBSCRIBE":
                handleUnsubscribe(headers);
                break;
            case "SEND":
                handleSend(headers, body);
                break;
            case "DISCONNECT":
                handleDisconnect(headers);
                break;
            default:
                sendError("Malformed Frame", "Unknown command: " + command, headers.get("receipt"));
        }

        return null;
    }

    private String parseHeadersAndBody(String[] lines, Map<String, String> headers) {
        boolean inBody = false;
        StringBuilder body = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (!inBody) {
                if (line.trim().isEmpty()) {
                    inBody = true;
                    continue;
                }
                int idx = line.indexOf(':');
                if (idx < 0) continue;
                String key = line.substring(0, idx).trim();
                String val = line.substring(idx + 1).trim();
                headers.put(key, val);
            } else {
                body.append(line);
                if (i < lines.length - 1) body.append("\n");
            }
        }
        return body.toString();
    }

    private boolean isLoggedIn() {
        return currentUser != null && currentUser.isLoggedIn();
    }

    private void handleConnect(Map<String, String> headers) {
        String receipt = headers.get("receipt");
        String login = headers.get("login");
        String passcode = headers.get("passcode");

        if (login == null || passcode == null) {
            sendError("Missing login/passcode", "Missing login/passcode", receipt);
            return;
        }

        if (isLoggedIn()) {
            sendError("Not logged in", "Client already logged in", receipt);
            return;
        }

        User already = activeByName.get(login);
        if (already != null && already.isLoggedIn()) {
            sendError("User already logged in", "User already logged in", receipt);
            return;
        }

        String storedPassword = db.getPassword(login);
        if (storedPassword == null) {
            boolean ok = db.registerUser(login, passcode);
            if (!ok) {
                sendError("SQL registration failed", "SQL registration failed", receipt);
                return;
            }
        } else {
            if (!storedPassword.equals(passcode)) {
                sendError("Wrong password", "Wrong password", receipt);
                return;
            }
        }

        currentUser = new User(connectionId, login, passcode);
        currentUser.login();
        activeByName.put(login, currentUser);
        activeByConn.put(connectionId, currentUser);

        db.logLogin(login);

        connections.send(connectionId, createConnectedFrame());
        if (receipt != null) {
            connections.send(connectionId, createReceiptFrame(receipt));
        }
    }

    private void handleSubscribe(Map<String, String> headers) {
        String receipt = headers.get("receipt");
        if (!isLoggedIn()) {
            sendError("Not logged in", "Not logged in", receipt);
            return;
        }

        String topic = headers.get("destination");
        String subId = headers.get("id");
        if (topic == null || subId == null) {
            sendError("Missing destination/id", "Missing destination/id", receipt);
            return;
        }

        if (!(connections instanceof ConnectionsImpl)) {
            sendError("Connections implementation mismatch", "Connections implementation mismatch", receipt);
            return;
        }

        ConnectionsImpl<String> connImpl = (ConnectionsImpl<String>) connections;
        connImpl.subscribe(topic, connectionId);
        currentUser.addSubscription(topic, subId);

        if (receipt != null) {
            connections.send(connectionId, createReceiptFrame(receipt));
        }
    }

    private void handleUnsubscribe(Map<String, String> headers) {
        String receipt = headers.get("receipt");
        if (!isLoggedIn()) {
            sendError("Not logged in", "Not logged in", receipt);
            return;
        }

        String subId = headers.get("id");
        if (subId == null) {
            sendError("Missing id", "Missing id", receipt);
            return;
        }

        String topic = currentUser.getTopic(subId);
        if (topic == null) {
            sendError("Not subscribed", "Not subscribed", receipt);
            return;
        }

        currentUser.removeSubscription(subId);

        if (!(connections instanceof ConnectionsImpl)) {
            sendError("Connections implementation mismatch", "Connections implementation mismatch", receipt);
            return;
        }

        ConnectionsImpl<String> connImpl = (ConnectionsImpl<String>) connections;
        connImpl.unsubscribe(topic, connectionId);

        if (receipt != null) {
            connections.send(connectionId, createReceiptFrame(receipt));
        }
    }

    private void handleSend(Map<String, String> headers, String body) {
        String receipt = headers.get("receipt");
        if (!isLoggedIn()) {
            sendError("Not logged in", "Not logged in", receipt);
            return;
        }

        String topic = headers.get("destination");
        if (topic == null) {
            sendError("Missing destination", "Missing destination", receipt);
            return;
        }

        if (currentUser.getSubscriptionId(topic) == null) {
            sendError("Not subscribed to topic", "Not subscribed to topic", receipt);
            return;
        }

        String filename = headers.get("file");
        if (filename != null && !filename.isEmpty()) {
            String key = currentUser.name + "|" + filename + "|" + topic;
            if (fileUploadOnce.putIfAbsent(key, Boolean.TRUE) == null) {
                db.trackFileUpload(currentUser.name, filename, topic);
            }
        }

        if (!(connections instanceof ConnectionsImpl)) {
            sendError("Connections implementation mismatch", "Connections implementation mismatch", receipt);
            return;
        }

        ConnectionsImpl<String> connImpl = (ConnectionsImpl<String>) connections;
        ConcurrentLinkedQueue<Integer> subscribers = connImpl.getSubscribers(topic);

        if (subscribers != null) {
            for (Integer targetConnId : subscribers) {
                User targetUser = activeByConn.get(targetConnId);
                if (targetUser == null) continue;

                String targetSubId = targetUser.getSubscriptionId(topic);
                if (targetSubId == null) continue;

                String msgFrame = createMessageFrame(topic, targetSubId, body);
                connections.send(targetConnId, msgFrame);
            }
        }

        if (receipt != null) {
            connections.send(connectionId, createReceiptFrame(receipt));
        }
    }

    private void handleDisconnect(Map<String, String> headers) {
        String receipt = headers.get("receipt");

        if (receipt != null) {
            connections.send(connectionId, createReceiptFrame(receipt));
        }

        cleanupAndDisconnect(false);
    }

    private void sendError(String shortMsg, String details, String receipt) {
        connections.send(connectionId, createErrorFrame(shortMsg, details, receipt));
        cleanupAndDisconnect(true);
    }

    private void cleanupAndDisconnect(boolean dueToError) {
        if (currentUser != null && currentUser.isLoggedIn()) {
            db.logLogout(currentUser.name);
        }

        if (connections instanceof ConnectionsImpl) {
            ((ConnectionsImpl<String>) connections).unsubscribeAll(connectionId);
        }

        if (currentUser != null) {
            activeByConn.remove(connectionId, currentUser);
            activeByName.remove(currentUser.name, currentUser);
            currentUser.clearSubscriptions();
            currentUser.logout();
        }

        shouldTerminate = true;
        connections.disconnect(connectionId);
    }

    private String createConnectedFrame() {
        return "CONNECTED\n" +
                "version:1.2\n" +
                "\n";
    }

    private String createReceiptFrame(String receiptId) {
        return "RECEIPT\n" +
                "receipt-id:" + receiptId + "\n" +
                "\n";
    }

    private String createErrorFrame(String shortMsg, String details, String receiptId) {
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR\n");
        sb.append("message:").append(shortMsg).append("\n");
        if (receiptId != null) {
            sb.append("receipt-id:").append(receiptId).append("\n");
        }
        sb.append("\n");
        sb.append("The message:\n----\n");
        sb.append(lastFrame).append("\n----\n");
        sb.append(details).append("\n");
        return sb.toString();
    }

    private String createMessageFrame(String topic, String subscriptionId, String body) {
        int msgId = messageIdCounter.incrementAndGet();
        return "MESSAGE\n" +
                "subscription:" + subscriptionId + "\n" +
                "message-id:" + msgId + "\n" +
                "destination:" + topic + "\n" +
                "\n" +
                (body == null ? "" : body);
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }
}
