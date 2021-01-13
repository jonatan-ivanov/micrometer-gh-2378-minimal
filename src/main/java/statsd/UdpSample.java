// Mainly a copy of https://github.com/micrometer-metrics/micrometer/blob/v1.5.8/implementations/micrometer-registry-statsd/src/main/java/io/micrometer/statsd/StatsdMeterRegistry.java\

package statsd;

import java.net.PortUnreachableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.Connection;
import reactor.netty.udp.UdpClient;
import reactor.util.retry.Retry;

public class UdpSample {
	private static final AttributeKey<Boolean> CONNECTION_DISPOSED = AttributeKey.valueOf("doOnDisconnectCalled");

	private final Collection<FakeMeter> pollableMeters = new ArrayList<>();
	private final AtomicBoolean started = new AtomicBoolean();
	private final Sinks.Many<String> manySink;
	private final Disposable.Swap statsdConnection = Disposables.swap();
	private final Disposable.Swap meterPoller = Disposables.swap();

	public static void main(String[] args) throws InterruptedException {
		new UdpSample().poll();
		Thread.sleep(Long.MAX_VALUE);
	}

	public UdpSample() {
		this.manySink = Sinks.many().multicast().directBestEffort();
		for (int i = 0; i < 100; i++) {
			pollableMeters.add(new FakeMeter(manySink));
		}

		start();
	}

	void poll() {
		for (FakeMeter pollableMeter : pollableMeters) {
			pollableMeter.poll();
		}
	}

	public void start() {
		if (started.compareAndSet(false, true)) {
			Publisher<String> publisher = BufferingFlux.create(
					this.manySink.asFlux(),
					"\n",
					1400,
					Duration.ofSeconds(10).toMillis()
			).onBackpressureLatest();
//			Publisher<String> publisher = this.manySink.asFlux();
			prepareUdpClient(publisher);
		}
	}

	private void prepareUdpClient(Publisher<String> publisher) {
		AtomicReference<UdpClient> udpClientReference = new AtomicReference<>();
		UdpClient udpClient = UdpClient.create()
				.host("localhost")
				.port(8125)
				.handle((in, out) -> out
						.sendString(publisher)
						.neverComplete()
						.retryWhen(Retry.indefinitely().filter(this::shouldRetry))
				)
				.doOnDisconnected(connection -> {
					Boolean connectionDisposed = connection.channel().attr(CONNECTION_DISPOSED).getAndSet(Boolean.TRUE);
					if (connectionDisposed == null || !connectionDisposed) {
						connectAndSubscribe(udpClientReference.get());
					}
				});
		udpClientReference.set(udpClient);
		connectAndSubscribe(udpClient);
	}

	private boolean shouldRetry(Throwable throwable) {
		if (throwable instanceof PortUnreachableException) {
			return true;
		}
		else {
			System.out.println(throwable);
			return false;
		}
	}

	private void connectAndSubscribe(UdpClient udpClient) {
		retryReplaceClient(Mono.defer(() -> {
			if (started.get()) {
				return udpClient.connect();
			}
			return Mono.empty();
		}));
	}

	private void retryReplaceClient(Mono<? extends Connection> connectMono) {
		connectMono
				.retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofMinutes(1)))
				.subscribe(connection -> {
					this.statsdConnection.replace(connection);

					// now that we're connected, start polling gauges and other pollable meter types
					startPolling();
				});
	}

	private void startPolling() {
		meterPoller.update(Flux.interval(Duration.ofSeconds(10))
				.doOnEach(n -> poll())
				.subscribe());
	}
}
