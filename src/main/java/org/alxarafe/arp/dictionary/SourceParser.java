package org.alxarafe.arp.dictionary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
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

final class SourceParser {
    private final Limits limits;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    SourceParser(Limits limits) { this.limits = limits; this.json = JsonSupport.reader(limits); }

    Object parse(Source source) {
        if (source.byteLength() > limits.maxDocumentBytes()) throw Rejected.limit();
        try {
            var text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source.content())).toString();
            Object value = source.encoding() == Source.Encoding.JSON ? json.readValue(text, Object.class) : yaml(text);
            JsonSupport.unicode(value);
            return value;
        } catch (StreamConstraintsException e) { throw Rejected.limit(); }
        catch (CharacterCodingException | JsonProcessingException | YamlEngineException | NumberFormatException e) {
            throw Rejected.document();
        }
    }

    private Object yaml(String text) {
        var settings = LoadSettings.builder().setSchema(new JsonSchema())
                .setAllowDuplicateKeys(false).setAllowRecursiveKeys(false)
                .setMaxAliasesForCollections(0).setCodePointLimit(limits.maxDocumentBytes()).build();
        // Inspect tokens too: parsers may discard unknown directives before events.
        var scanner = new ScannerImpl(settings, new StreamReader(settings, text));
        while (scanner.hasNext()) {
            var token = scanner.next();
            if (token instanceof DirectiveToken<?> || token instanceof AliasToken
                    || token instanceof AnchorToken || token instanceof TagToken) throw Rejected.document();
        }
        int documents = 0, depth = 0;
        for (var event : new Parse(settings).parseString(text)) {
            if (event instanceof DocumentStartEvent doc) {
                if (++documents != 1 || doc.getSpecVersion().isPresent() || !doc.getTags().isEmpty()) throw Rejected.document();
            }
            if (event instanceof AliasEvent) throw Rejected.document();
            if (event instanceof NodeEvent node && node.getAnchor().isPresent()) throw Rejected.document();
            if (event instanceof ScalarEvent scalar && scalar.getTag().isPresent()) throw Rejected.document();
            if (event instanceof CollectionStartEvent collection && collection.getTag().isPresent()) throw Rejected.document();
            if (event instanceof CollectionStartEvent && ++depth > limits.maxNestingDepth()) throw Rejected.limit();
            if (event instanceof CollectionEndEvent) depth--;
        }
        if (documents != 1) throw Rejected.document();
        // Compose only: no Java object constructors, tag handlers or external resolvers.
        return convert(new Compose(settings).composeString(text).orElseThrow(Rejected::document));
    }

    private Object convert(Node node) {
        if (node instanceof MappingNode mapping) {
            var map = new LinkedHashMap<String, Object>();
            for (var tuple : mapping.getValue()) {
                Object key = convert(tuple.getKeyNode());
                if (!(key instanceof String s) || s.equals("<<") || map.containsKey(s)) throw Rejected.document();
                map.put(s, convert(tuple.getValueNode()));
            }
            return map;
        }
        if (node instanceof SequenceNode sequence) {
            var list = new ArrayList<>();
            for (var child : sequence.getValue()) list.add(convert(child));
            return list;
        }
        if (!(node instanceof ScalarNode scalar)) throw Rejected.document();
        String text = scalar.getValue();
        Tag tag = scalar.getTag();
        if (tag.equals(Tag.STR)) return text;
        if (tag.equals(Tag.NULL)) return null;
        if (tag.equals(Tag.BOOL)) return Boolean.valueOf(text);
        if (tag.equals(Tag.INT)) return new BigInteger(text);
        if (tag.equals(Tag.FLOAT)) return new BigDecimal(text);
        throw Rejected.document();
    }
}
