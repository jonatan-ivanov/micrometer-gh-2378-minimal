// Copied from https://github.com/micrometer-metrics/micrometer/blob/v1.5.8/implementations/micrometer-registry-statsd/src/main/java/io/micrometer/statsd/internal/BufferingFlux.java
package statsd;


import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

public class BufferingFlux {

	private BufferingFlux() {
	}

	/**
	 * Creates a Flux that implements Nagle's algorithm to buffer messages -- joined by a delimiter string -- to up a
	 * maximum number of bytes, or a maximum duration of time. This avoids sending many small packets in favor of fewer
	 * larger ones.
	 *
	 * @param source                      The input flux.
	 * @param delimiter                   The delimiter to use to join messages
	 * @param maxByteArraySize            The buffered payload will contain no more than this number of bytes
	 * @param maxMillisecondsBetweenEmits Buffered payloads will be emitted no less frequently than this.
	 * @return A flux implementing Nagle's algorithm.
	 * @see <a href="https://en.wikipedia.org/wiki/Nagle%27s_algorithm">Nagle's algorithm</a>
	 */
	public static Flux<String> create(final Flux<String> source, final String delimiter, final int maxByteArraySize, final long maxMillisecondsBetweenEmits) {
		return Flux.defer(() -> {
			final int delimiterSize = delimiter.getBytes().length;
			final AtomicInteger byteSize = new AtomicInteger();
			final AtomicLong lastTime = new AtomicLong();

			final Sinks.Empty<Void> intervalEnd = Sinks.empty();

			final Flux<String> heartbeat = Flux.interval(Duration.ofMillis(maxMillisecondsBetweenEmits))
					.map(l -> "")
					.takeUntilOther(intervalEnd.asMono());

			// Create a stream that emits at least once every $maxMillisecondsBetweenEmits, to avoid long pauses between
			// buffer flushes when the source doesn't emit for a while.
			final Flux<String> sourceWithEmptyStringKeepAlive = source
					.doOnTerminate(intervalEnd::tryEmitEmpty)
					.mergeWith(heartbeat);

			return sourceWithEmptyStringKeepAlive
					.bufferUntil(line -> {
						final int bytesLength = line.getBytes().length;
						final long now = System.currentTimeMillis();
						// Update last time to now if this is the first time
						lastTime.compareAndSet(0, now);
						final long last = lastTime.get();
						long diff;
						if (last != 0L) {
							diff = now - last;
							if (diff > maxMillisecondsBetweenEmits && byteSize.get() > 0) {
								// This creates a buffer, reset size
								byteSize.set(bytesLength);
								lastTime.compareAndSet(last, now);
								return true;
							}
						}

						int additionalBytes = bytesLength;
						if (additionalBytes > 0 && byteSize.get() > 0) {
							additionalBytes += delimiterSize;  // Make up for the delimiter that's added when joining the strings
						}

						final int projectedBytes = byteSize.addAndGet(additionalBytes);

						if (projectedBytes > maxByteArraySize) {
							// This creates a buffer, reset size
							byteSize.set(bytesLength);
							lastTime.compareAndSet(last, now);
							return true;
						}

						return false;
					}, true)
					.map(lines -> lines.stream()
							.filter(line -> !line.isEmpty())
							.collect(Collectors.joining(delimiter, "", delimiter)));
		});
	}
}
