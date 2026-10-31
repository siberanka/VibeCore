package com.siberanka.vibecore.project;

import com.siberanka.vibecore.core.validation.MapReader;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Identity block shared by every project document: stable identifier, type,
 * schema version and a revision that increases on every accepted change.
 */
public record ProjectHeader(Identifier id, ProjectType type, int schemaVersion, long revision, String locale) {

    public ProjectHeader {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(locale, "locale");
        if (schemaVersion < 1 || revision < 0) {
            throw new IllegalArgumentException("schemaVersion must be >= 1 and revision >= 0");
        }
    }

    /** Reads the header of a document that has already been migrated. */
    public static Result<ProjectHeader> read(Map<String, ?> document) {
        MapReader reader = MapReader.root(document);
        Result<Identifier> id = Identifier.parse("id", reader.text("id", "", 200));
        String typeKey = reader.text("type", "", 32);
        ProjectType type = ProjectType.fromKey(typeKey).orElse(null);
        if (type == null) {
            reader.report("type", "unknown-type", "unknown project type '" + typeKey + "'");
        }
        int schema = reader.integer(MigrationChain.VERSION_KEY, 1, 1, Integer.MAX_VALUE);
        int revision = reader.integer("revision", 0, 0, Integer.MAX_VALUE);
        String locale = reader.text("locale", "en", 16);
        List<Problem> problems = new ArrayList<>(reader.problems());
        problems.addAll(id.problems());
        if (!problems.isEmpty()) {
            return Result.rejected(problems);
        }
        return Result.accepted(new ProjectHeader(id.orThrow(), type, schema, revision, locale));
    }
}
