package org.alxarafe.arp.dictionary;

/** Trusted caller identities and a JSON Pointer; never part of a descriptor's projection. */
public record Provenance(String providerId, String sourceId, String path) {}
