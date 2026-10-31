package com.siberanka.vibecore.variable;

import com.siberanka.vibecore.core.validation.Result;
import java.util.UUID;

/**
 * Who a variable read or write is about. The subject is the player whose
 * value is addressed; the session is one open screen. A context never
 * implies permission to act for the subject: callers check that separately.
 */
public record VariableContext(UUID subject, UUID session) {

    public static final VariableContext GLOBAL = new VariableContext(null, null);

    public static VariableContext player(UUID subject) {
        return new VariableContext(subject, null);
    }

    public static VariableContext session(UUID subject, UUID session) {
        return new VariableContext(subject, session);
    }

    /** Checks that the context names everything the scope needs. */
    Result<VariableContext> require(VariableScope scope, String field) {
        if (scope.playerBound() && subject == null) {
            return Result.rejected(field, "subject", scope.key() + " variables need a subject player");
        }
        if (scope == VariableScope.SESSION && session == null) {
            return Result.rejected(field, "session", "session variables need an open screen session");
        }
        return Result.accepted(this);
    }
}
