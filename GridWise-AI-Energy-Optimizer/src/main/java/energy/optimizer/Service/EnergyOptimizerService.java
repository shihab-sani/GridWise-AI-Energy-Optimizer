package energy.optimizer.Service;

import energy.optimizer.DTOs.OptimizationDtos.*;
import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class EnergyOptimizerService {
    public OptimizeResponse optimize(OptimizeRequest req, List<DirectiveInterpretation> interpretations) {
        int N = 24;
        double[] effectiveSolar = new double[N];
        double[] minReserve = new double[N];
        boolean[] noCharge = new boolean[N];
        boolean[] noDischarge = new boolean[N];
        double[] maxGridCap = new double[N];
        Arrays.fill(maxGridCap, Double.MAX_VALUE);

        for (int h = 0; h < N; h++) {
            effectiveSolar[h] = req.hours().get(h).solarKwh();
            minReserve[h] = req.battery().minimumEnergyKwh();
        }

        // Apply validated directives
        for (DirectiveInterpretation interp : interpretations) {
            if (!interp.applies() || interp.structuredAdjustment() == null) continue;

            Map<String, Object> adj = interp.structuredAdjustment();
            List<Integer> hours = (List<Integer>) adj.get("hours");
            if (hours == null) continue;

            switch (interp.directiveType()) {
                case "solar_reduction" -> {
                    double factor = ((Number) adj.get("factor")).doubleValue();
                    for (int h : hours) effectiveSolar[h] *= factor;
                }
                case "minimum_battery_reserve" -> {
                    double reqMin = ((Number) adj.get("minimum_energy_kwh")).doubleValue();
                    for (int h : hours) minReserve[h] = Math.max(minReserve[h], reqMin);
                }
                case "no_charge_window" -> {
                    for (int h : hours) noCharge[h] = true;
                }
                case "no_discharge_window" -> {
                    for (int h : hours) noDischarge[h] = true;
                }
                case "max_grid_window" -> {
                    double cap = ((Number) adj.get("max_grid_kwh")).doubleValue();
                    for (int h : hours) maxGridCap[h] = Math.min(maxGridCap[h], cap);
                }
            }
        }

        // Build OjAlgo LP Model
        ExpressionsBasedModel model = new ExpressionsBasedModel();

        Variable[] g = new Variable[N]; // grid_kwh
        Variable[] s = new Variable[N]; // solar_used_kwh
        Variable[] c = new Variable[N]; // battery_charge_kwh
        Variable[] d = new Variable[N]; // battery_discharge_kwh
        Variable[] e = new Variable[N]; // battery_energy_after_kwh

        for (int h = 0; h < N; h++) {
            double tariff = req.hours().get(h).tariffBdtPerKwh();

            g[h] = model.addVariable("grid_" + h).lower(0.0).upper(maxGridCap[h]).weight(tariff);
            s[h] = model.addVariable("solar_" + h).lower(0.0).upper(effectiveSolar[h]);
            c[h] = model.addVariable("charge_" + h).lower(0.0).upper(noCharge[h] ? 0.0 : req.battery().maxChargeKwhPerHour());
            d[h] = model.addVariable("discharge_" + h).lower(0.0).upper(noDischarge[h] ? 0.0 : req.battery().maxDischargeKwhPerHour());
            e[h] = model.addVariable("energy_" + h).lower(minReserve[h]).upper(req.battery().capacityKwh());
        }

        // Energy balance & battery state transition constraints
        for (int h = 0; h < N; h++) {
            double demand = req.hours().get(h).demandKwh();

            // Constraint: grid_h + solar_h + discharge_h - charge_h = demand_h
            Expression balance = model.addExpression("balance_" + h).level(demand);
            balance.set(g[h], 1.0);
            balance.set(s[h], 1.0);
            balance.set(d[h], 1.0);
            balance.set(c[h], -1.0);

            // Constraint: energy_h - energy_{h-1} - charge_h + discharge_h = 0
            Expression battTransition = model.addExpression("batt_trans_" + h).level(0.0);
            battTransition.set(e[h], 1.0);
            if (h == 0) {
                battTransition.level(req.battery().initialEnergyKwh());
            } else {
                battTransition.set(e[h - 1], -1.0);
            }
            battTransition.set(c[h], -1.0);
            battTransition.set(d[h], 1.0);
        }

        // End-of-day battery neutrality constraint: energy_23 == initial_energy
        Expression neutrality = model.addExpression("eod_neutrality").level(req.battery().initialEnergyKwh());
        neutrality.set(e[23], 1.0);

        // Solve LP
        Optimisation.Result result = model.minimise();

        // 🛑 FIX: Check if the model is solvable to prevent NullPointerException
        if (!result.getState().isFeasible()) {
            // Handle the infeasible case (e.g., throw a custom exception)
            throw new IllegalArgumentException("The optimization model is infeasible with the given operator directives.");
        }

        // Extract Schedule
        List<HourlyPlan> hourlyPlan = new ArrayList<>();
        double totalGrid = 0.0;
        double totalCost = 0.0;
        double peakGrid = 0.0;

        for (int h = 0; h < N; h++) {
            double gridVal = Math.max(0.0, g[h].getValue().doubleValue());
            double solarVal = Math.max(0.0, s[h].getValue().doubleValue());
            double chargeVal = Math.max(0.0, c[h].getValue().doubleValue());
            double dischargeVal = Math.max(0.0, d[h].getValue().doubleValue());
            double energyVal = Math.max(0.0, e[h].getValue().doubleValue());

            String action = "idle";
            double battKwh = 0.0;
            if (chargeVal > 0.001) {
                action = "charge";
                battKwh = chargeVal;
            } else if (dischargeVal > 0.001) {
                action = "discharge";
                battKwh = dischargeVal;
            }

            double tariff = req.hours().get(h).tariffBdtPerKwh();
            totalGrid += gridVal;
            totalCost += gridVal * tariff;
            peakGrid = Math.max(peakGrid, gridVal);

            hourlyPlan.add(new HourlyPlan(h, round(gridVal), round(solarVal), action, round(battKwh), round(energyVal)));
        }

        String summary = "Optimized energy schedule over 24 hours. Minimal grid cost BDT " + round(totalCost) +
                " with peak grid draw of " + round(peakGrid) + " kWh.";

        return new OptimizeResponse(
                req.scenarioId(),
                interpretations,
                hourlyPlan,
                round(totalGrid),
                round(totalCost),
                round(peakGrid),
                summary
        );
    }

    private double round(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}