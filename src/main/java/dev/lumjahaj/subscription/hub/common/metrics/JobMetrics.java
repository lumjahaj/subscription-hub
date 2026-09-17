package dev.lumjahaj.subscription.hub.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for the scheduled jobs, which are otherwise invisible: they have no
 * request to show up in http_server_requests, and they catch their own
 * per-item failures so that one bad subscription or invoice does not stop the
 * run - which also means nothing outside the log ever hears about one.
 *
 * - jobs.run (timer; scheduled.job, outcome): how long each run took, and whether the
 *   run as a whole failed (the tenant list itself could not be read, say).
 * - jobs.item.failures (counter; scheduled.job, step): items the run caught and skipped.
 *   A run that "succeeds" while failing every item shows up here.
 * - jobs.last.success (gauge, epoch seconds; scheduled.job): the alertable one. A job
 *   that stopped running - scheduler wedged, cron disabled, instance gone -
 *   produces no failure at all, only an absence, and this is how an absence
 *   becomes visible: time() - jobs_last_success_seconds keeps growing.
 *
 * Job names are fixed strings chosen by each job, never derived from data, so
 * the tag stays three values.
 */
@Component
public class JobMetrics {

    /**
     * Not "job": Prometheus attaches its own job label (the scrape target) to
     * every series, and on a clash renames the application's label to
     * exported_job - so a rule filtering on job="billing-cycle" silently
     * matches nothing. Found by scraping a running instance, not by a test.
     */
    static final String JOB_TAG = "scheduled.job";

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> lastSuccess = new ConcurrentHashMap<>();

    public JobMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void run(String job, Runnable body) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "failure";
        try {
            body.run();
            outcome = "success";
            lastSuccessOf(job).set(registry.config().clock().wallTime() / 1000);
        } finally {
            sample.stop(Timer.builder("jobs.run")
                    .description("Scheduled job runs")
                    .tag(JOB_TAG, job)
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }

    public void itemFailed(String job, String step) {
        Counter.builder("jobs.item.failures")
                .description("Items a scheduled job caught, logged and skipped")
                .tag(JOB_TAG, job)
                .tag("step", step)
                .register(registry)
                .increment();
    }

    private AtomicLong lastSuccessOf(String job) {
        return lastSuccess.computeIfAbsent(job, name -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder("jobs.last.success", holder, AtomicLong::get)
                    .description("When the job last completed a run, as epoch seconds")
                    .baseUnit("seconds")
                    .tag(JOB_TAG, name)
                    .register(registry);
            return holder;
        });
    }
}
