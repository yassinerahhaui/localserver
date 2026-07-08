package utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final long DEFAULT_MAX_IDLE_TIME = 30 * 60 * 1000L; // 30 minutes in ms

    public static Session getSession(String id) {
        if (id == null) return null;
        Session session = SESSIONS.get(id);
        if (session != null) {
            if (session.isExpired(DEFAULT_MAX_IDLE_TIME)) {
                SESSIONS.remove(id);
                return null;
            }
            session.updateAccessTime();
        }
        return session;
    }

    public static Session createSession() {
        Session session = new Session();
        SESSIONS.put(session.getId(), session);
        return session;
    }

    public static void removeSession(String id) {
        if (id != null) {
            SESSIONS.remove(id);
        }
    }

    public static Map<String, Session> getActiveSessions() {
        // Clean up expired sessions first
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(entry -> entry.getValue().isExpired(DEFAULT_MAX_IDLE_TIME));
        return SESSIONS;
    }

    public static int getActiveSessionsCount() {
        return getActiveSessions().size();
    }
}
