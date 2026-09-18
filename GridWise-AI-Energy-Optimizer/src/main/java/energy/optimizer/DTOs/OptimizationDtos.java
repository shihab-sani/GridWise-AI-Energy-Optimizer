package energy.optimizer.DTOs;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public class OptimizationDtos {
    public record HourInput(
            int hour,
            @JsonProperty("demand_kwh") double demandKwh,
            @JsonProperty("solar_kwh") double solarKwh,
            @JsonProperty("tariff_bdt_per_kwh") double tariffBdtPerKwh
    ) {}

    public record BatteryInput(
            @JsonProperty("capacity_kwh") double capacityKwh,
            @JsonProperty("initial_energy_kwh") double initialEnergyKwh,
            @JsonProperty("minimum_energy_kwh") double minimumEnergyKwh,
            @JsonProperty("max_charge_kwh_per_hour") double maxChargeKwhPerHour,
            @JsonProperty("max_discharge_kwh_per_hour") double maxDischargeKwhPerHour
    ) {}

    public record OptimizeRequest(
            @JsonProperty("scenario_id") String scenarioId,
            @JsonProperty("operator_notes") List<String> operatorNotes,
            List<HourInput> hours,
            BatteryInput battery
    ) {}

    public record DirectiveInterpretation(
            @JsonProperty("note_index") int noteIndex,
            boolean applies,
            @JsonProperty("directive_type") String directiveType,
            @JsonProperty("structured_adjustment") Map<String, Object> structuredAdjustment,
            String explanation
    ) {}

    public record HourlyPlan(
            int hour,
            @JsonProperty("grid_kwh") double gridKwh,
            @JsonProperty("solar_used_kwh") double solarUsedKwh,
            @JsonProperty("battery_action") String batteryAction,
            @JsonProperty("battery_kwh") double batteryKwh,
            @JsonProperty("battery_energy_after_kwh") double batteryEnergyAfterKwh
    ) {}

    public record OptimizeResponse(
            @JsonProperty("scenario_id") String scenarioId,
            @JsonProperty("directive_interpretation") List<DirectiveInterpretation> directiveInterpretation,
            @JsonProperty("hourly_plan") List<HourlyPlan> hourlyPlan,
            @JsonProperty("total_grid_kwh") double totalGridKwh,
            @JsonProperty("total_cost_bdt") double totalCostBdt,
            @JsonProperty("peak_grid_kwh") double peakGridKwh,
            @JsonProperty("plan_summary") String planSummary
    ) {}
}
