package com.jinddung2.couponlab.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/** Small helpers shared by the S6 integration tests: queue depth from rabbitmqctl, and waiting for a condition. */
public final class S6Containers {

    private S6Containers() {
    }

    /** Messages (ready + unacked) of a queue as rabbitmqctl reports them; -1 when the queue is not listed. */
    public static long queueMessages(GenericContainer<?> rabbitmq, String queue) {
        ExecResult listing;
        try {
            listing = rabbitmq.execInContainer("rabbitmqctl", "list_queues", "-q", "name", "messages");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        for (String line : listing.getStdout().split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length == 2 && fields[0].equals(queue)) {
                return Long.parseLong(fields[1]);
            }
        }
        return -1;
    }

    /** Polls until the condition holds; an exception while checking (the database just came back, say) counts as "not yet". */
    public static void await(String what, Duration max, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        while (!holds(condition)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out after " + max + " waiting for " + what);
            }
            Thread.sleep(100);
        }
    }

    private static boolean holds(BooleanSupplier condition) {
        try {
            return condition.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
