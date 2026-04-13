package com.aicomm.agent;

import com.aicomm.domain.Conversation;

/**
 * ThreadLocal holder for the current conversation being processed.
 * Set before agent.chat(), read by @Tool methods, cleared in finally block.
 */
public final class ConversationContext {

    private static final ThreadLocal<Conversation> CURRENT = new ThreadLocal<>();

    private ConversationContext() {}

    public static void set(Conversation conversation) {
        CURRENT.set(conversation);
        com.aicomm.agent.tools.ConversationTools.resetCallCount();
    }

    public static Conversation get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
        com.aicomm.agent.tools.ConversationTools.clearCallCount();
    }
}
