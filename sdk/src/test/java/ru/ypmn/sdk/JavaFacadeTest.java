package ru.ypmn.sdk;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import ru.ypmn.sdk.CreateIntentRequest;
import ru.ypmn.sdk.java.Cancellable;
import ru.ypmn.sdk.java.IntentEventListener;
import ru.ypmn.sdk.java.YPJava;
import ru.ypmn.sdk.java.YpCallback;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class JavaFacadeTest {
    @Test public void createIntent_delivers_via_callback_and_listeners_are_callable() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("{\"id\":\"i1\",\"status\":\"RequiresPaymentData\",\"secret\":\"s\"}"));
        server.start();

        YpConfig config = new YpConfig(server.url("/").toString(), Collections.emptyMap());
        CreateIntentRequest req = CreateIntentRequest.builder("t1", 1L, "RUB", "SMS").build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Intent> result = new AtomicReference<>();
        YpCallback<Intent> cb = new YpCallback<Intent>() {
            @Override public void onSuccess(Intent r) { result.set(r); latch.countDown(); }
            @Override public void onError(Throwable e) { latch.countDown(); }
        };

        Cancellable c = YPJava.createIntent(req, config, cb);
        assertTrue("callback must fire", latch.await(5, TimeUnit.SECONDS));
        assertNotNull(result.get());
        assertEquals("i1", result.get().getId());

        // addEventListener / removeEventListener are callable from Java and return a Cancellable
        IntentEventListener listener = event -> { };
        Cancellable sub = result.get().addEventListener(listener);
        sub.cancel();
        result.get().removeEventListener(listener);

        server.shutdown();
    }

    @Test public void getIntent_via_callback_and_completable_future() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("{\"id\":\"i2\",\"status\":\"RequiresPaymentData\",\"secret\":\"s\"}"));
        server.enqueue(new MockResponse().setBody("{\"id\":\"i2\",\"status\":\"RequiresPaymentData\",\"secret\":\"s\"}"));
        server.start();
        YpConfig config = new YpConfig(server.url("/").toString(), Collections.emptyMap());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Intent> viaCb = new AtomicReference<>();
        YPJava.getIntent("i2", config, new YpCallback<Intent>() {
            @Override public void onSuccess(Intent r) { viaCb.set(r); latch.countDown(); }
            @Override public void onError(Throwable e) { latch.countDown(); }
        });
        assertTrue("callback must fire", latch.await(5, TimeUnit.SECONDS));
        assertEquals("i2", viaCb.get().getId());

        java.util.concurrent.CompletableFuture<Intent> future = YPJava.getIntentFuture("i2", config);
        assertEquals("i2", future.get(5, TimeUnit.SECONDS).getId());

        server.shutdown();
    }
}
