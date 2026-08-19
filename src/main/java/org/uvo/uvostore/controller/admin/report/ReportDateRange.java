package org.uvo.uvostore.controller.admin.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

// Shared start_date/end_date -> Instant conversion (startOfDay/endOfDay in UTC), matching every
// Admin\Reports\* Livewire component's Carbon::parse(...)->startOfDay()/endOfDay() pair.
final class ReportDateRange {

    private ReportDateRange() {
    }

    static Instant start(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static Instant end(LocalDate date) {
        return date.atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant();
    }
}
