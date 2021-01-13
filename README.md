# gh-2378-minimal

Sample project to reproduce https://github.com/micrometer-metrics/micrometer/issues/2378

You can observe the issue in two ways:
1. The simple but slow way 
	1. Start `UdpSample` and let it run for a few minutes, after a while it will log a lot of errors, see below
1. The fast but more complicated way
	1. Start `UdpSample` in debug mode
   	1. Put a breakpoint into the first line of the `poll` method
	1. Wait a few seconds and release the execution (F9 in IntelliJ)
	1. After 10s, the debugger will stop on this line again, wait a few seconds and release again, after a few loops it will log a lot of errors, see below

When the original issue happens, the client is trapped into an infinite retry loop, where the `Throwable` is the following: `reactor.core.Exceptions$OverflowException: Backpressure overflow during Sinks.Many#emitNext`.
This project is visualizing the `Throwable` in the retry loop instead of the connection logs.
