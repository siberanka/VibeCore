package com.siberanka.vibecore.core.text;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Locale-keyed message templates. The fallback locale defines the complete
 * key set; other locales must provide the same keys and nothing more, so a
 * translation cannot silently miss or invent a message.
 */
public final class MessageCatalog {

    public static final String FALLBACK_LOCALE = "en";
    public static final Set<String> SUPPORTED_LOCALES = Set.of("en", "tr");
    private static final int MAX_TEMPLATE_LENGTH = 2048;

    private final Map<String, Map<String, String>> templates;

    private MessageCatalog(Map<String, Map<String, String>> templates) {
        this.templates = templates;
    }

    /**
     * Builds a catalog from bundled templates and optional operator overrides.
     * Overrides may replace templates but cannot introduce unknown keys.
     */
    public static Result<MessageCatalog> build(Map<String, Map<String, String>> bundled,
                                               Map<String, Map<String, String>> overrides) {
        List<Problem> problems = new ArrayList<>();
        Map<String, String> reference = bundled.get(FALLBACK_LOCALE);
        if (reference == null || reference.isEmpty()) {
            return Result.rejected("lang/" + FALLBACK_LOCALE, "missing", "fallback messages are missing");
        }
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();
        for (String locale : new TreeSet<>(SUPPORTED_LOCALES)) {
            Map<String, String> base = bundled.getOrDefault(locale, Map.of());
            Map<String, String> combined = new LinkedHashMap<>(base);
            combined.putAll(overrides.getOrDefault(locale, Map.of()));
            String file = "lang/" + locale;
            for (String key : reference.keySet()) {
                if (!combined.containsKey(key)) {
                    problems.add(new Problem(file + "." + key, "missing-message", "translation is missing"));
                }
            }
            for (Map.Entry<String, String> entry : combined.entrySet()) {
                if (!reference.containsKey(entry.getKey())) {
                    problems.add(new Problem(file + "." + entry.getKey(), "unknown-message", "unknown message key"));
                } else if (entry.getValue() == null || entry.getValue().length() > MAX_TEMPLATE_LENGTH) {
                    problems.add(new Problem(file + "." + entry.getKey(), "length",
                            "must be text of at most " + MAX_TEMPLATE_LENGTH + " characters"));
                }
            }
            merged.put(locale, Map.copyOf(combined));
        }
        for (String locale : overrides.keySet()) {
            if (!SUPPORTED_LOCALES.contains(locale)) {
                problems.add(new Problem("lang/" + locale, "unsupported", "unsupported locale"));
            }
        }
        if (!problems.isEmpty()) {
            return Result.rejected(problems);
        }
        return Result.accepted(new MessageCatalog(Map.copyOf(merged)));
    }

    /** Maps a client or configured language tag onto a supported locale. */
    public static String resolveLocale(String languageTag, String defaultLocale) {
        if (languageTag != null) {
            String language = languageTag.replace('-', '_').split("_", 2)[0].toLowerCase(java.util.Locale.ROOT);
            if (SUPPORTED_LOCALES.contains(language)) {
                return language;
            }
        }
        return SUPPORTED_LOCALES.contains(defaultLocale) ? defaultLocale : FALLBACK_LOCALE;
    }

    public String template(String locale, String key) {
        Map<String, String> localized = templates.getOrDefault(locale, templates.get(FALLBACK_LOCALE));
        String template = localized.get(key);
        if (template == null) {
            throw new IllegalArgumentException("Unknown message key: " + key);
        }
        return template;
    }

    public Set<String> keys() {
        return templates.get(FALLBACK_LOCALE).keySet();
    }
}
