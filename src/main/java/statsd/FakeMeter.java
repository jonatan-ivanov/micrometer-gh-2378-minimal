package statsd;

import reactor.core.publisher.Sinks;

public class FakeMeter {
	private final Sinks.Many<String> sink;

	public FakeMeter(Sinks.Many<String> sink) {
		this.sink = sink;
	}

	public void poll() {
		sink.emitNext("jvm.memory.max:1073741824|g|#statistic:value,area:nonheap,id:Compressed Class Space", Sinks.EmitFailureHandler.FAIL_FAST);
	}
}
