package org.alxarafe.arp.dictionary;

/** Expected input rejection only; unrelated runtime/resource failures are not swallowed. */
final class Rejected extends RuntimeException {
    final Code code;
    Rejected(Code code) { super(code.name()); this.code = code; }
    static Rejected document() { return new Rejected(Code.DICTIONARY_DOCUMENT_INVALID); }
    static Rejected element() { return new Rejected(Code.DICTIONARY_ELEMENT_INVALID); }
    static Rejected type() { return new Rejected(Code.DICTIONARY_VALUE_TYPE); }
    static Rejected limit() { return new Rejected(Code.DICTIONARY_LIMIT_EXCEEDED); }
}
