package dev.ebms.adapter.out.cpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.Party;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Component
public class YamlCpaRepository implements CpaRepository {

    private static final Logger log = LoggerFactory.getLogger(YamlCpaRepository.class);

    private final Map<String, Cpa> cpas = new ConcurrentHashMap<>();
    private final Path cpaDirectory;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public YamlCpaRepository(@Value("${ebms.cpa-directory}") String cpaDirectory) {
        this.cpaDirectory = Path.of(cpaDirectory);
    }

    @PostConstruct
    public void reload() {
        cpas.clear();
        if (!Files.exists(cpaDirectory)) {
            log.warn("CPA directory does not exist: {}", cpaDirectory);
            return;
        }
        try (Stream<Path> files = Files.list(cpaDirectory)) {
            files.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                    .forEach(this::loadFile);
        } catch (IOException e) {
            log.error("Failed to read CPA directory: {}", cpaDirectory, e);
        }
        log.info("Loaded {} CPA(s) from {}", cpas.size(), cpaDirectory);
    }

    @Override
    public Optional<Cpa> findByCpaId(String cpaId) {
        return Optional.ofNullable(cpas.get(cpaId));
    }

    @Override
    public List<Cpa> findAll() {
        return List.copyOf(cpas.values());
    }

    private void loadFile(Path file) {
        try {
            CpaFileConfig config = yaml.readValue(file.toFile(), CpaFileConfig.class);
            Cpa cpa = new Cpa(
                    config.cpaId,
                    new Party(config.fromParty.partyId, config.fromParty.partyIdType),
                    new Party(config.toParty.partyId, config.toParty.partyIdType),
                    config.transportUrl,
                    config.ackRequested,
                    config.duplicateElimination,
                    config.retries,
                    Duration.ofSeconds(config.retryIntervalSeconds)
            );
            cpas.put(cpa.cpaId(), cpa);
            log.debug("Loaded CPA {} from {}", cpa.cpaId(), file.getFileName());
        } catch (IOException e) {
            log.error("Failed to parse CPA file: {}", file, e);
        }
    }
}
