package dev.ebms.domain.exception;

/**
 * A CPA document that cannot be projected into something the MSH can route.
 *
 * <p>Deliberately not an {@link IllegalArgumentException}: Spring's persistence exception
 * translation rewrites those when they escape a {@code @Repository}, because JPA uses them to
 * signal API misuse. The caller would then see an {@code InvalidDataAccessApiUsageException}
 * and answer 500 to what is a bad request.
 */
public class InvalidCpaException extends RuntimeException {

    public InvalidCpaException(String message, Throwable cause) {
        super(message, cause);
    }
}
