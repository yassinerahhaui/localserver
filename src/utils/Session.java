package utils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Session {
    private final String id;
    private final long creationTime;
    private long lastAccessedTime;
    private final Map<String, Object> attributes;

    public Session() {
        this.id = UUID.randomUUID().toString();
        this.creationTime = System.currentTimeMillis();
        this.lastAccessedTime = this.creationTime;
        this.attributes = new HashMap<>();
    }

    public String getId() {
        return id;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public long getLastAccessedTime() {
        return lastAccessedTime;
    }

    public void updateAccessTime() {
        this.lastAccessedTime = System.currentTimeMillis();
    }

    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public boolean isExpired(long maxIdleTimeMs) {
        return (System.currentTimeMillis() - lastAccessedTime) > maxIdleTimeMs;
    }
}
