package dev.ebms.domain;

public record Payload(String contentId, String mimeType, byte[] content) {}
