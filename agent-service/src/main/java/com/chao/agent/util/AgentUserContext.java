package com.chao.agent.util;

public final class AgentUserContext {
    private AgentUserContext() {}

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    public static void set(Long userId) {
        HOLDER.set(userId);
    }

    public static Long get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
