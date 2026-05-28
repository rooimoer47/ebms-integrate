package dev.ebms.domain;

import java.util.Arrays;
import java.util.Objects;

public record Payload(String contentId, String mimeType, byte[] content) {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof Payload(var cId, var mType, var c)
                && Objects.equals(contentId, cId)
                && Objects.equals(mimeType, mType)
                && Arrays.equals(content, c);
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
