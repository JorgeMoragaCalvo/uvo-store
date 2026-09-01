package org.uvo.uvostore;

import io.sentry.Sentry;
import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

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

        // .env is registered explicitly. spring-dotenv 4.x announced itself through
        // META-INF/spring.factories, which Spring Boot 4 no longer reads, so the dependency sat on
        // the classpath doing nothing and .env was silently ignored — nobody noticed because every
        // property in it happened to match its own default in application.properties. 5.x ships no
        // auto-registration at all, so wiring the initializer here (rather than relying on any
        // discovery mechanism) is both the fix and the thing that can't silently rot again.
        new SpringApplicationBuilder(UvoStoreApplication.class)
                .initializers(new DotenvApplicationInitializer())
                .run(args);
    }

}
