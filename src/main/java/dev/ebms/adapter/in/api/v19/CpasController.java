package dev.ebms.adapter.in.api.v19;

import dev.ebms.adapter.out.cpa.CpaDocumentParser;
import dev.ebms.adapter.out.cpa.CpaValidator;
import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.domain.Cpa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The CPA lifecycle cpa-service drives, at the paths it ships as its default
 * ({@code http://ebms-core:8888/service/rest/v19/cpas}).
 *
 * <p>Taken from {@code EbmsCoreCpaClient} rather than from documentation: six operations, and
 * every one of them has a detail that is not in the OpenAPI document.
 */
@RestController
@RequestMapping("/service/rest/v19/cpas")
public class CpasController {

    private static final Logger log = LoggerFactory.getLogger(CpasController.class);

    private final CpaRepository cpas;
    private final CpaValidator validator;

    public CpasController(CpaRepository cpas, CpaValidator validator) {
        this.cpas = cpas;
        this.validator = validator;
    }

    /**
     * Store a CPA, answering with its id.
     *
     * <p>The body is taken as {@code byte[]}, not {@code String}. Spring's
     * {@code StringHttpMessageConverter} decodes a {@code text/*} body using the charset in the
     * Content-Type and a default when there is none -- and cpa-service sets none it controls. A
     * CPA carrying a non-ASCII party name would be stored mangled and nothing downstream would
     * notice. Decoding is left to the XML prolog, which is the only thing that actually knows.
     */
    @PostMapping(consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE,
            MediaType.TEXT_XML_VALUE, MediaType.ALL_VALUE},
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> insertCpa(
            @RequestBody byte[] body,
            @RequestParam(name = "overwrite", defaultValue = "false") boolean overwrite) {

        String document = XmlDocuments.decode(body);
        Cpa cpa = validator.validate(document, CpaDocumentParser.XML, "upload",
                CpaValidator.Strictness.FULL);

        if (cpas.existsByCpaId(cpa.cpaId()) && !overwrite) {
            // ebms-core answers 500 here, from an IllegalArgumentException its handler does not
            // special-case. cpa-service treats 4xx and 5xx identically ("CPA is invalid"), so a
            // truthful status costs nothing and 500 for "you sent this twice" is not worth
            // preserving.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("CPA " + cpa.cpaId() + " already exists. Post with overwrite=true to "
                            + "replace it.");
        }

        cpas.save(cpa.cpaId(), document, CpaDocumentParser.XML, "api");
        log.info("Stored CPA {} (overwrite={})", cpa.cpaId(), overwrite);
        return ResponseEntity.ok(cpa.cpaId());
    }

    /**
     * Validate without storing.
     *
     * <p>cpa-service turns any 4xx or 5xx into "CPA is invalid" and shows an operator the body,
     * so the status carries nothing and the body carries everything.
     */
    @PostMapping(path = "/validate", consumes = {MediaType.TEXT_PLAIN_VALUE,
            MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<Void> validateCpa(@RequestBody byte[] body) {
        validator.validate(XmlDocuments.decode(body), CpaDocumentParser.XML, "validate",
                CpaValidator.Strictness.FULL);
        return ResponseEntity.ok().build();
    }

    /**
     * The stored ids, as a JSON array of bare strings -- cpa-service deserialises into
     * {@code List<String>}, not into the object list {@code /api/cpas} returns.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> listCpaIds() {
        return cpas.findAllCpaIds();
    }

    /**
     * The stored document itself. Reverse-sync parses this back into cpa-service's own table,
     * so it has to be the agreement rather than a rendering of our projection.
     *
     * <p>The charset is explicit: a {@code text/*} response without one is ISO-8859-1 to a
     * strict client.
     */
    @GetMapping(path = "/{cpaId}", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> getCpa(@PathVariable String cpaId) {
        return resolve(cpaId, cpas::findDocument)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Remove a CPA. Answers 204 whether or not it existed, which is a deliberate divergence.
     *
     * <p>ebms-core answers 404 for an unknown CPA. cpa-service's {@code deleteCPA} catches that,
     * logs it -- and then rethrows unconditionally, outside the {@code if}. Its caller catches
     * only {@code CPAServiceException}, so the 404 propagates past the two lines that delete
     * cpa-service's own row and its alias: the console reports a failure and the CPA stays.
     * Answering 204 either way costs us nothing, because the caller asked for the CPA to be gone
     * and it is gone.
     *
     * <p>No {@code consumes} on the mapping: cpa-service sends this with
     * {@code Content-Type: application/json} and no body.
     */
    @DeleteMapping("/{cpaId}")
    public ResponseEntity<Void> deleteCpa(@PathVariable String cpaId) {
        boolean existed = resolve(cpaId, id -> Optional.of(id).filter(cpas::existsByCpaId))
                .map(cpas::deleteByCpaId)
                .orElse(false);
        log.info("Delete CPA {}: {}", cpaId, existed ? "removed" : "not present");
        return ResponseEntity.noContent().build();
    }

    /**
     * Rebuild the projection from the store.
     *
     * <p>Never touches the table -- the path says cache. This is the only way a change made
     * anywhere other than through this API becomes visible.
     *
     * <p>Spring prefers the literal pattern over {@code /{cpaId}}, so a CPA whose id is
     * literally {@code cache} is unreachable here. Recorded rather than worked around.
     */
    @DeleteMapping("/cache")
    public ResponseEntity<Void> clearCache() {
        cpas.reload();
        return ResponseEntity.noContent().build();
    }

    /**
     * Resolves a cpaId that may have been encoded twice.
     *
     * <p>cpa-service URL-encodes the id itself ({@code Sanitizer.urlEncode}, form encoding, so
     * {@code $} becomes {@code %24}) and then hands the result to a RestClient path variable,
     * which percent-encodes it again. Spring MVC decodes once, so what arrives here is the
     * literal text {@code DGL-ONTVANGEN-1-0%241-0_...}. Production ids contain {@code $}.
     *
     * <p>ebms-core does not handle this either -- its JAX-RS {@code @PathParam} is decoded once
     * and passed straight to its repository -- so ids containing characters the encoder escapes
     * silently 404 there today. Ids of letters, digits, {@code -} and {@code _} are untouched by
     * the encoder, which is why the {@code DGL_AFN_...} family works and nobody has noticed.
     *
     * <p>The decode is a fallback, not a step: decoding unconditionally would corrupt an id that
     * legitimately contains a {@code %}.
     */
    private <T> Optional<T> resolve(String cpaId, java.util.function.Function<String, Optional<T>> lookup) {
        Optional<T> direct = lookup.apply(cpaId);
        if (direct.isPresent() || (!cpaId.contains("%") && !cpaId.contains("+"))) {
            return direct;
        }
        try {
            String decoded = URLDecoder.decode(cpaId, StandardCharsets.UTF_8);
            if (decoded.equals(cpaId)) {
                return direct;
            }
            log.debug("Resolved cpaId {} as {} after one decode", cpaId, decoded);
            return lookup.apply(decoded);
        } catch (IllegalArgumentException e) {
            return direct;
        }
    }
}
