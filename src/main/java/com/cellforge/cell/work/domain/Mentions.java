package com.cellforge.cell.work.domain;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds {@code @handle} mentions in a comment. A handle is matched later against user ids and the local part
 * of member emails; anything that matches nobody is just text.
 */
public final class Mentions {

    // Not preceded by a word character, so "kunal@example.com" is not a mention of "example.com".
    private static final Pattern MENTION = Pattern.compile("(?<![\\w.])@([A-Za-z0-9][A-Za-z0-9._-]{0,63})");

    private Mentions() {
    }

    public static Set<String> in(String body) {
        Set<String> handles = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(body);
        while (m.find()) {
            String handle = m.group(1);
            while (handle.endsWith(".") || handle.endsWith("-")) {
                handle = handle.substring(0, handle.length() - 1);
            }
            handles.add(handle.toLowerCase());
        }
        return handles;
    }
}
