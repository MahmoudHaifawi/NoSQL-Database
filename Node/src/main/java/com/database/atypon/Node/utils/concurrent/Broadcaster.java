package com.database.atypon.Node.utils.concurrent;

import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** Runs a set of tasks in parallel, waits for all of them, and returns one result each. */
public final class Broadcaster {
    private Broadcaster() {}

    private static final int MAX_THREADS = 4;

    public static List<Response> broadcast(List<Supplier<Response>> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return new ArrayList<>();
        }
        int poolSize = Math.min(MAX_THREADS, tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            List<Callable<Response>> callables = new ArrayList<>(tasks.size());
            for (Supplier<Response> task : tasks) {
                callables.add(task::get);
            }
            List<Future<Response>> futures = executor.invokeAll(callables); // blocks until all done
            List<Response> results = new ArrayList<>(futures.size());
            for (Future<Response> future : futures) {
                results.add(resultOf(future));
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of(new Response(ResponseType.ERROR, "Broadcast interrupted"));
        } finally {
            executor.shutdown();
        }
    }

    private static Response resultOf(Future<Response> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            return new Response(ResponseType.ERROR, "Broadcast task failed: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(ResponseType.ERROR, "Broadcast task interrupted");
        }
    }
}
