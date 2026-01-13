package bgu.spl.net.impl.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class User {
    public final String name;
    public final String password;

    private volatile int connectionId;
    private volatile boolean isLoggedIn = false;

    // topic -> subscription-id
    private final Map<String, String> topicToSubId = new ConcurrentHashMap<>();
    // subscription-id -> topic
    private final Map<String, String> subIdToTopic = new ConcurrentHashMap<>();

    public User(int connectionId, String name, String password) {
        this.connectionId = connectionId;
        this.name = name;
        this.password = password;
    }

    public boolean isLoggedIn() { return isLoggedIn; }
    public void login() { isLoggedIn = true; }
    public void logout() { isLoggedIn = false; }

    public int getConnectionId() { return connectionId; }
    public void setConnectionId(int connectionId) { this.connectionId = connectionId; }

    public void addSubscription(String topic, String subId) {
        topicToSubId.put(topic, subId);
        subIdToTopic.put(subId, topic);
    }

    public String getSubscriptionId(String topic) {
        return topicToSubId.get(topic);
    }

    public String getTopic(String subId) {
        return subIdToTopic.get(subId);
    }

    public void removeSubscription(String subId) {
        String topic = subIdToTopic.remove(subId);
        if (topic != null) {
            topicToSubId.remove(topic);
        }
    }

    public void clearSubscriptions() {
        topicToSubId.clear();
        subIdToTopic.clear();
    }
}