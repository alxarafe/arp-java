package org.alxarafe.arp.dictionary;

import com.fasterxml.jackson.core.JsonToken;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.*;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.*;
import org.snakeyaml.engine.v2.schema.JsonSchema;
import org.snakeyaml.engine.v2.scanner.ScannerImpl;
import org.snakeyaml.engine.v2.scanner.StreamReader;
import org.snakeyaml.engine.v2.tokens.*;

final class SourceParser
{
    private static final java.util.regex.Pattern JSON_NUMBER = java.util.regex.Pattern.compile(
            "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private final Limits limits;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    SourceParser(Limits limits)
    {
        this.limits = limits;
        this.json = JsonSupport.reader(limits);
    }

    Object parse(Source source)
    {
        if (source.byteLength() > limits.maxDocumentBytes()) {
            throw Rejected.limit();
        }
        try {
            var text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source.content()))
                    .toString();
            Object value = source.encoding() == Source.Encoding.JSON ? json(text) : yaml(text);
            JsonSupport.unicode(value);
            return value;
        } catch (StreamConstraintsException e) {
            throw Rejected.limit();
        } catch (java.io.IOException | YamlEngineException | NumberFormatException e) {
            throw Rejected.document();
        }
    }

    // Inspect raw tokens before a host numeric conversion can overflow or round.
    private Object json(String text) throws java.io.IOException
    {
        try (var parser = json.createParser(text)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token.isNumeric()) {
                    sourceNumber(parser.getText());
                }
            }
        }
        return json.readValue(text, Object.class);
    }

    static Number sourceNumber(String token)
    {
        if (token.length() > 256) {
            throw Rejected.limit();
        }
        int e = Math.max(token.indexOf('e'), token.indexOf('E'));
        if (e >= 0) {
            String exponent = token.substring(e + 1).replaceFirst("^[+-]", "").replaceFirst("^0+", "");
            if (exponent.length() > 3 || (!exponent.isEmpty() && Integer.parseInt(exponent) > 128)) {
                throw Rejected.limit();
            }
        }
        var number = new BigDecimal(token);
        if (number.abs().compareTo(BigDecimal.TEN.pow(128)) >= 0) {
            throw Rejected.limit();
        }
        return e < 0 && token.indexOf('.') < 0 ? new BigInteger(token) : number;
    }

    private Object yaml(String text)
    {
        if (text.indexOf('\u0085') >= 0 || text.indexOf('\u2028') >= 0 || text.indexOf('\u2029') >= 0
                || text.replace("\r\n", "").indexOf('\r') >= 0) {
            throw Rejected.document();
        }
        var settings = LoadSettings.builder().setSchema(new JsonSchema())
                .setAllowDuplicateKeys(false).setAllowRecursiveKeys(false)
                .setMaxAliasesForCollections(0).setCodePointLimit(limits.maxDocumentBytes()).build();
        // Inspect tokens too: parsers may discard unknown directives before events.
        int[] characterOffsets = new int[text.codePointCount(0, text.length()) + 1];
        for (int point = 0, offset = 0; offset < text.length(); point++) {
            characterOffsets[point] = offset;
            offset += Character.charCount(text.codePointAt(offset));
        }
        characterOffsets[characterOffsets.length - 1] = text.length();
        var scanner = new ScannerImpl(settings, new StreamReader(settings, text));
        while (scanner.hasNext()) {
            var token = scanner.next();
            if (token instanceof ScalarToken scalar) {
                var start = scalar.getStartMark().orElseThrow();
                var end = scalar.getEndMark().orElseThrow();
                int begin = characterOffsets[start.getIndex()];
                int finish = characterOffsets[end.getIndex()];
                String raw = text.substring(begin, finish);
                var style = scalar.getStyle();
                if (style == ScalarStyle.LITERAL || style == ScalarStyle.FOLDED) {
                    String header = raw.split("\\r?\\n", 2)[0];
                    if (!header.matches("[|>][+-]?(?:[ \t]+(?:#.*)?)?")) {
                        throw Rejected.document();
                    }
                } else {
                    if (start.getLine() != end.getLine()) {
                        throw Rejected.document();
                    }
                    if (style == ScalarStyle.DOUBLE_QUOTED) {
                        try {
                            if (!Unicode.valid(json.readValue(raw, String.class))) {
                                throw Rejected.document();
                            }
                        } catch (java.io.IOException invalid) {
                            throw Rejected.document();
                        }
                    }
                    if (style == ScalarStyle.PLAIN && JSON_NUMBER.matcher(scalar.getValue()).matches()) {
                        sourceNumber(scalar.getValue());
                    }
                }
            }
            if (token instanceof DirectiveToken<?> || token instanceof AliasToken
                    || token instanceof AnchorToken || token instanceof TagToken) {
                throw Rejected.document();
            }
        }
        int documents = 0, depth = 0;
        for (var event : new Parse(settings).parseString(text)) {
            if (event instanceof DocumentStartEvent doc) {
                if (++documents != 1 || doc.getSpecVersion().isPresent() || !doc.getTags().isEmpty()) {
                    throw Rejected.document();
                }
            }
            if (event instanceof AliasEvent) {
                throw Rejected.document();
            }
            if (event instanceof NodeEvent node && node.getAnchor().isPresent()) {
                throw Rejected.document();
            }
            if (event instanceof ScalarEvent scalar && scalar.getTag().isPresent()) {
                throw Rejected.document();
            }
            if (event instanceof CollectionStartEvent collection && collection.getTag().isPresent()) {
                throw Rejected.document();
            }
            if (event instanceof CollectionStartEvent && ++depth > limits.maxNestingDepth()) {
                throw Rejected.limit();
            }
            if (event instanceof CollectionEndEvent) {
                depth--;
            }
        }
        if (documents != 1) {
            throw Rejected.document();
        }
        // Compose only: no Java object constructors, tag handlers or external resolvers.
        return convert(new Compose(settings).composeString(text).orElseThrow(Rejected::document));
    }

    private Object convert(Node node)
    {
        if (node instanceof MappingNode mapping) {
            var map = new LinkedHashMap<String, Object>();
            for (var tuple : mapping.getValue()) {
                Object key = convert(tuple.getKeyNode());
                if (!(key instanceof String s) || s.equals("<<") || map.containsKey(s)) {
                    throw Rejected.document();
                }
                map.put(s, convert(tuple.getValueNode()));
            }
            return map;
        }
        if (node instanceof SequenceNode sequence) {
            var list = new ArrayList<>();
            for (var child : sequence.getValue()) {
                list.add(convert(child));
            }
            return list;
        }
        if (!(node instanceof ScalarNode scalar)) {
            throw Rejected.document();
        }
        String text = scalar.getValue();
        if (scalar.getScalarStyle() != ScalarStyle.PLAIN) {
            return text;
        }
        if (text.isEmpty() || text.equals("null")) {
            return null;
        }
        if (text.equals("true") || text.equals("false")) {
            return Boolean.valueOf(text);
        }
        if (JSON_NUMBER.matcher(text).matches()) {
            return sourceNumber(text);
        }
        return text;
    }
}
