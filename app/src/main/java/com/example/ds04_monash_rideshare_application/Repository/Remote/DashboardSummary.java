package com.example.ds04_monash_rideshare_application.Repository.Remote;

import java.util.List;

//Sustainability dashboard summary (item 8) - CO2 avoided is clearly an estimate, not a
//measurement (distance x vehicle emission factor, see backend Emissions.kt).
public class DashboardSummary {
    public final int totalRidesCompleted;
    public final double totalCo2AvoidedKg;
    public final List<MonthlySummary> monthlyTripSummary;

    public DashboardSummary(int totalRidesCompleted, double totalCo2AvoidedKg, List<MonthlySummary> monthlyTripSummary) {
        this.totalRidesCompleted = totalRidesCompleted;
        this.totalCo2AvoidedKg = totalCo2AvoidedKg;
        this.monthlyTripSummary = monthlyTripSummary;
    }

    public static class MonthlySummary {
        public final String monthLabel;
        public final int tripsCompleted;
        public final double co2AvoidedKg;

        public MonthlySummary(String monthLabel, int tripsCompleted, double co2AvoidedKg) {
            this.monthLabel = monthLabel;
            this.tripsCompleted = tripsCompleted;
            this.co2AvoidedKg = co2AvoidedKg;
        }
    }
}
