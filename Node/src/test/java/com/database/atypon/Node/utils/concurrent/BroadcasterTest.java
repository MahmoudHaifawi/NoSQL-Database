package com.database.atypon.Node.utils.concurrent;

import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class BroadcasterTest {

    @Test
    void waitsForEveryTaskAndReturnsAllResults() {
        List<Supplier<Response>> tasks = List.of(
                slowOk("a"), slowOk("b"), slowOk("c"));

        List<Response> results = Broadcaster.broadcast(tasks);

        assertThat(results).hasSize(3);
        assertThat(results).allMatch(r -> r.getResponseType() == ResponseType.SUCCESS);
    }

    @Test
    void mapsThrownExceptionToErrorResponse() {
        List<Supplier<Response>> tasks = List.of(
                slowOk("a"),
                () -> { throw new RuntimeException("boom"); });

        List<Response> results = Broadcaster.broadcast(tasks);

        assertThat(results).hasSize(2);
        assertThat(results).filteredOn(r -> r.getResponseType() == ResponseType.SUCCESS).hasSize(1);
        assertThat(results).filteredOn(r -> r.getResponseType() == ResponseType.ERROR).hasSize(1);
    }

    @Test
    void callerInterruptedWhileBlockedReturnsOneResponsePerTask() throws InterruptedException {
        List<Supplier<Response>> tasks = List.of(
                slowOk("a"), slowOk("b"), slowOk("c"));
        AtomicReference<List<Response>> captured = new AtomicReference<>();

        Thread worker = new Thread(() -> captured.set(Broadcaster.broadcast(tasks)));
        worker.start();
        Thread.sleep(20);
        worker.interrupt();
        worker.join();

        assertThat(captured.get()).hasSize(tasks.size());
    }

    private static Supplier<Response> slowOk(String msg) {
        return () -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            return new Response(ResponseType.SUCCESS, msg);
        };
    }
}
