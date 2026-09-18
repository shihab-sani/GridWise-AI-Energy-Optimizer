package energy.optimizer.Controller;

import energy.optimizer.DTOs.OptimizationDtos.*;
import energy.optimizer.Service.EnergyOptimizerService;
import energy.optimizer.Service.LlmInterpreterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class GridWiseController {
    private final LlmInterpreterService interpreterService;
    private final EnergyOptimizerService optimizerService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> getHealth() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/optimize-energy")
    public ResponseEntity<?> optimizeEnergy(@RequestBody OptimizeRequest request) {
        // 1. Validate Input
        if (request == null || request.hours() == null || request.hours().size() != 24) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request must contain exactly 24 hours."));
        }
        if (request.operatorNotes() == null || request.operatorNotes().isEmpty() || request.operatorNotes().size() > 3) {
            return ResponseEntity.badRequest().body(Map.of("error", "operator_notes must contain 1 to 3 items."));
        }
        if (request.battery() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Battery configuration is required."));
        }

        try {
            // 2. Interpret Notes via LLM
            List<DirectiveInterpretation> interpretations = interpreterService.interpretNotes(request.operatorNotes());

            // 3. Optimize
            OptimizeResponse response = optimizerService.optimize(request, interpretations);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // Catch infeasible optimization errors
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // Catch unexpected errors
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "An unexpected error occurred during optimization."));
        }
    }
}