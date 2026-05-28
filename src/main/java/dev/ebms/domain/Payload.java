package dev.ebms.domain;

import java.util.Arrays;
import java.util.Objects;

public record Payload(String contentId, String mimeType, byte[] content) {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Payload p)) return false;
        return Objects.equals(contentId, p.contentId)
                && Objects.equals(mimeType, p.mimeType)
                && Arrays.equals(content, p.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contentId, mimeType, Arrays.hashCode(content));
    }

    @Override
    public String toString() {
        return "Payload[contentId=" + contentId + ", mimeType=" + mimeType
                + ", content=" + Arrays.toString(content) + "]";
    }
}
