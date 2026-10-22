package com.tianmu.agent.resource;

import java.io.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Enforces the response limit while bytes arrive, before allocating or parsing an unbounded body. */
public final class BoundedHttpBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    public BoundedHttpBody(int limit) { this.limit = limit; }
    public CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
        for (var buffer:buffers) {
            if (bytes.size()+buffer.remaining()>limit) {
                subscription.cancel(); result.completeExceptionally(new IOException("Response limit exceeded")); return;
            }
            byte[] part=new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
        }
        subscription.request(1);
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(bytes.toByteArray()); }
}
