package mx.astro.simulation.infrastructure.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of(
                "service", "simulation-service",
                "status", "skeleton",
                "version", "0.1.0-SNAPSHOT"
        );
    }
}
