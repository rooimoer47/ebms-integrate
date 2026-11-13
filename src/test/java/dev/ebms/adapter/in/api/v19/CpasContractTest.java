package dev.ebms.adapter.in.api.v19;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the CPA controller against ebms-core's own API document.
 *
 * <p>{@code docs/compat/ebms-core-cpas-api.json} is thinner than the message contract -- all
 * {@code default} responses, no schemas beyond the id array -- so what it pins is routes,
 * methods and media types. That is still the part a client breaks on: cpa-service's
 * {@code EbmsCoreCpaClient} appends these suffixes to its configured base and deserialises what
 * comes back, so a renamed path or a changed media type is a production failure in another
 * repository.
 *
 * <p>Read from the annotations rather than from a running context: this asserts what the code
 * declares, which is what a reviewer changing a mapping would otherwise have to remember.
 */
class CpasContractTest {

    private static final Path SPEC = Path.of("docs/compat/ebms-core-cpas-api.json");

    /**
     * Deliberately not implemented: ebms-core serves its own OpenAPI document here, which is
     * not part of the client contract (story D8).
     */
    private static final Set<String> OUT_OF_SCOPE = Set.of("/api-docs/{resource}");

    private static JsonNode paths;

    @BeforeAll
    static void loadSpec() throws Exception {
        assertThat(Files.exists(SPEC))
                .as("vendored CPA contract at %s -- see docs/compat/README.md", SPEC)
                .isTrue();
        paths = JsonMapper.builder().build().readTree(Files.readString(SPEC)).get("paths");
    }

    private record Route(String method, String path) implements Comparable<Route> {
        @Override
        public int compareTo(Route other) {
            return (method + " " + path).compareTo(other.method + " " + other.path);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /** Every operation the vendored document declares, minus what is out of scope. */
    private static Set<Route> contractRoutes() {
        Set<Route> routes = new TreeSet<>();
        for (Map.Entry<String, JsonNode> entry : paths.properties()) {
            if (OUT_OF_SCOPE.contains(entry.getKey())) {
                continue;
            }
            // "/" in the document is the collection itself.
            String path = "/".equals(entry.getKey()) ? "" : entry.getKey();
            for (String method : entry.getValue().propertyNames()) {
                routes.add(new Route(method.toUpperCase(), path));
            }
        }
        return routes;
    }

    /** Every route the controller declares. */
    private static Set<Route> implementedRoutes() {
        Set<Route> routes = new TreeSet<>();
        for (Method method : CpasController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                routes.add(new Route("GET", firstPath(get.path(), get.value())));
            }
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (post != null) {
                routes.add(new Route("POST", firstPath(post.path(), post.value())));
            }
            DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
            if (delete != null) {
                routes.add(new Route("DELETE", firstPath(delete.path(), delete.value())));
            }
        }
        return routes;
    }

    private static String firstPath(String[] path, String[] value) {
        String[] declared = path.length > 0 ? path : value;
        return declared.length == 0 ? "" : declared[0];
    }

    @Test
    void everyContractOperationIsImplemented() {
        Set<Route> missing = new TreeSet<>(contractRoutes());
        missing.removeAll(implementedRoutes());

        assertThat(missing)
                .as("declared in %s but not served; cpa-service calls these", SPEC)
                .isEmpty();
    }

    @Test
    void nothingIsServedThatTheContractDoesNotDeclare() {
        Set<Route> extra = new TreeSet<>(implementedRoutes());
        extra.removeAll(contractRoutes());

        assertThat(extra)
                .as("served but not in the contract -- dead weight, or a path a client will "
                        + "never call")
                .isEmpty();
    }

    @Test
    void theControllerSitsAtTheBaseTheClientsAreConfiguredWith() {
        RequestMapping mapping = CpasController.class.getAnnotation(RequestMapping.class);

        assertThat(mapping.value())
                .as("global.ebms.core.cpa_service.url points here")
                .containsExactly("/service/rest/v19/cpas");
    }

    /** The media types the client's converters depend on, which the document also declares. */
    @Test
    void mediaTypesMatchTheContract() {
        List<String> mismatches = new ArrayList<>();

        String insertProduces = produces("insertCpa");
        if (!insertProduces.contains("text/plain")) {
            mismatches.add("insertCpa must produce text/plain, the bare cpaId -- got " + insertProduces);
        }
        String listProduces = produces("listCpaIds");
        if (!listProduces.contains("application/json")) {
            mismatches.add("listCpaIds must produce a JSON array of strings -- got " + listProduces);
        }
        String getProduces = produces("getCpa");
        if (!getProduces.contains("text/plain")) {
            mismatches.add("getCpa must produce text/plain, the document -- got " + getProduces);
        }
        if (!getProduces.toLowerCase().contains("charset=utf-8")) {
            mismatches.add("getCpa must state its charset: a text/* response without one is "
                    + "ISO-8859-1 to a strict client -- got " + getProduces);
        }

        assertThat(mismatches).isEmpty();
    }

    private static String produces(String methodName) {
        for (Method method : CpasController.class.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            if (method.isAnnotationPresent(GetMapping.class)) {
                return String.join(",", method.getAnnotation(GetMapping.class).produces());
            }
            if (method.isAnnotationPresent(PostMapping.class)) {
                return String.join(",", method.getAnnotation(PostMapping.class).produces());
            }
        }
        throw new AssertionError("No mapping found on CpasController." + methodName);
    }

    /**
     * The insert has to accept what cpa-service sends. Its client declares no content type, so
     * RestClient writes the String body as text/plain -- and a stricter {@code consumes} would
     * answer 415 to every upload.
     */
    @Test
    void insertAcceptsThePlainTextBodyTheClientSends() {
        PostMapping mapping = null;
        for (Method method : CpasController.class.getDeclaredMethods()) {
            if (method.getName().equals("insertCpa")) {
                mapping = method.getAnnotation(PostMapping.class);
            }
        }

        assertThat(mapping).isNotNull();
        assertThat(List.of(mapping.consumes()).stream().collect(Collectors.joining(",")))
                .contains("text/plain");
    }
}
