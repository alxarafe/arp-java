package org.alxarafe.arp.dictionary;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Formal ARP grammar and bounded dynamic matcher, with no host regex extensions. */
final class PortablePattern {
    private record Atom(BitSet characters, int minimum, int maximum) {}
    private record Member(char character, boolean rawHyphen) {}
    private final List<Atom> atoms;

    PortablePattern(String pattern) {
        if (pattern.codePointCount(0, pattern.length()) > 512) throw Rejected.limit();
        if (pattern.isEmpty() || pattern.chars().anyMatch(c -> c < 32 || c > 126)) throw Rejected.element();
        var parsed = new ArrayList<Atom>();
        int i = pattern.charAt(0) == '^' ? 1 : 0, end = pattern.length();
        int slashes = 0;
        for (int j = end - 2; j >= 0 && pattern.charAt(j) == '\\'; j--) slashes++;
        if (pattern.charAt(end - 1) == '$' && slashes % 2 == 0) end--;
        while (i < end) {
            var characters = new BitSet(128);
            char c = pattern.charAt(i++);
            if (c == '\\') {
                if (i >= end || !punctuation(pattern.charAt(i))) throw Rejected.element();
                characters.set(pattern.charAt(i++));
            } else if (c == '[') {
                var members = new ArrayList<Member>();
                while (i < end && pattern.charAt(i) != ']') {
                    char member = pattern.charAt(i++);
                    if (member == '\\') {
                        if (i >= end || !punctuation(pattern.charAt(i))) throw Rejected.element();
                        members.add(new Member(pattern.charAt(i++), false));
                    } else {
                        if (member == '[' || member == '^') throw Rejected.element();
                        members.add(new Member(member, member == '-'));
                    }
                }
                if (i == end || members.isEmpty()) throw Rejected.element();
                i++;
                if (members.getFirst().rawHyphen()) characters.set(members.removeFirst().character());
                if (!members.isEmpty() && members.getLast().rawHyphen()) characters.set(members.removeLast().character());
                for (int j = 0; j < members.size();) {
                    var low = members.get(j);
                    if (low.rawHyphen()) throw Rejected.element();
                    if (j + 1 < members.size() && members.get(j + 1).rawHyphen()) {
                        if (j + 2 >= members.size() || members.get(j + 2).rawHyphen()) throw Rejected.element();
                        char high = members.get(j + 2).character();
                        if (low.character() > high) throw Rejected.element();
                        characters.set(low.character(), high + 1);
                        j += 3;
                    } else { characters.set(low.character()); j++; }
                }
            } else {
                if (".^$*+?{}[]\\()|".indexOf(c) >= 0) throw Rejected.element();
                characters.set(c);
            }
            int minimum = 1, maximum = 1;
            if (i < end && "?*+".indexOf(pattern.charAt(i)) >= 0) {
                char quantifier = pattern.charAt(i++);
                minimum = quantifier == '+' ? 1 : 0;
                maximum = quantifier == '?' ? 1 : 4096;
            } else if (i < end && pattern.charAt(i) == '{') {
                int close = pattern.indexOf('}', i);
                if (close < 0 || close >= end) throw Rejected.element();
                var counts = pattern.substring(i + 1, close).split(",", -1);
                if (counts.length > 2) throw Rejected.element();
                minimum = count(counts[0]);
                maximum = count(counts[counts.length - 1]);
                if (minimum > maximum) throw Rejected.element();
                i = close + 1;
            }
            parsed.add(new Atom(characters, minimum, maximum));
        }
        if (parsed.isEmpty()) throw Rejected.element();
        atoms = List.copyOf(parsed);
    }

    private static int count(String value) {
        if (!value.matches("0|[1-9][0-9]{0,3}")) throw Rejected.element();
        int count = Integer.parseInt(value);
        if (count > 1024) throw Rejected.element();
        return count;
    }

    private static boolean punctuation(char c) {
        return c >= 33 && c <= 47 || c >= 58 && c <= 64 || c >= 91 && c <= 96 || c >= 123 && c <= 126;
    }

    boolean matches(String value) {
        if (!Unicode.valid(value)) throw Rejected.type();
        int n = value.codePointCount(0, value.length());
        if (n > 4096) throw Rejected.limit();
        int[] input = value.codePoints().toArray();
        boolean[] positions = new boolean[n + 1];
        positions[0] = true;
        for (var atom : atoms) {
            int[] prefix = new int[n + 2];
            for (int j = 0; j <= n; j++) prefix[j + 1] = prefix[j] + (positions[j] ? 1 : 0);
            boolean[] next = new boolean[n + 1];
            int runStart = 0;
            for (int end = 0; end <= n; end++) {
                if (end > 0 && !atom.characters().get(input[end - 1])) runStart = end;
                int low = Math.max(runStart, end - atom.maximum()), high = end - atom.minimum();
                if (high >= low && prefix[high + 1] > prefix[low]) next[end] = true;
            }
            positions = next;
        }
        return positions[n];
    }
}
