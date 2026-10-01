package io.github.qianlixunbai.workspace.provider.ollama;

import io.github.qianlixunbai.workspace.common.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final long limit;
    private long received;
    private Flow.Subscription subscription;
    private boolean failed;

    LimitedBodySubscriber(long limit) { this.limit = limit; }
    public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        delegate.onSubscribe(subscription);
    }
    public void onNext(List<ByteBuffer> items) {
        if (failed) return;
        for (ByteBuffer item : items) received += item.remaining();
        if (received > limit) {
            failed = true;
            subscription.cancel();
            delegate.onError(new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE"));
        } else delegate.onNext(items);
    }
    public void onError(Throwable throwable) { if (!failed) delegate.onError(throwable); }
    public void onComplete() { if (!failed) delegate.onComplete(); }
}
