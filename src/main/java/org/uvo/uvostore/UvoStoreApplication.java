package org.uvo.uvostore;

import io.sentry.Sentry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UvoStoreApplication {

    public static void main(String[] args) {
        // Read directly from the environment (not Spring's Environment, which isn't up yet this
        // early) — same "off until configured" pattern as every other external integration in
        // this project. Sentry.init() itself no-ops when the dsn is blank/null, no extra guard
        // needed. See GlobalExceptionHandler for where unhandled exceptions get reported.
        String sentryDsn = System.getenv("SENTRY_DSN");
        if (sentryDsn != null && !sentryDsn.isBlank()) {
            Sentry.init(options -> options.setDsn(sentryDsn));
        }

        SpringApplication.run(UvoStoreApplication.class, args);
    }

}
