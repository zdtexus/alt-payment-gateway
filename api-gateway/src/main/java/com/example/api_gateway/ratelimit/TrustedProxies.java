package com.example.api_gateway.ratelimit;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class TrustedProxies {

	private static final TrustedProxies NONE = new TrustedProxies(List.of());

	private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

	private static final Pattern PREFIX = Pattern.compile("\\d{1,3}");

	private final List<Range> ranges;

	private TrustedProxies(List<Range> ranges) {
		this.ranges = ranges;
	}

	public static TrustedProxies none() {
		return NONE;
	}

	public static TrustedProxies valueOf(String entries) {
		if (entries == null || entries.isBlank()) {
			return NONE;
		}
		List<Range> ranges = Arrays.stream(entries.split(","))
			.map(String::strip)
			.filter((entry) -> !entry.isEmpty())
			.map(TrustedProxies::parse)
			.toList();
		return new TrustedProxies(ranges);
	}

	public boolean isEmpty() {
		return ranges.isEmpty();
	}

	public boolean contains(InetAddress address) {
		if (address == null) {
			return false;
		}
		byte[] bytes = address.getAddress();
		return ranges.stream().anyMatch((range) -> range.matches(bytes));
	}

	@Override
	public String toString() {
		return ranges.stream().map(Range::toString).collect(Collectors.joining(", ", "[", "]"));
	}

	private static Range parse(String entry) {
		int slash = entry.indexOf('/');
		String host = (slash < 0) ? entry : entry.substring(0, slash);
		InetAddress address = parseAddress(entry, host);
		int maxPrefix = address.getAddress().length * Byte.SIZE;
		int prefix = (slash < 0) ? maxPrefix : parsePrefix(entry, entry.substring(slash + 1), maxPrefix);
		return new Range(address, prefix);
	}

	static InetAddress parseLiteral(String text) {
		if (!text.contains(":") && !IPV4.matcher(text).matches()) {
			return null;
		}
		try {
			return InetAddress.ofLiteral(text);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static InetAddress parseAddress(String entry, String host) {
		InetAddress address = parseLiteral(host);
		if (address == null) {
			throw invalid(entry, "not an IP address");
		}
		return address;
	}

	private static int parsePrefix(String entry, String text, int maxPrefix) {
		if (!PREFIX.matcher(text).matches() || Integer.parseInt(text) > maxPrefix) {
			throw invalid(entry, "prefix length must be 0-" + maxPrefix);
		}
		return Integer.parseInt(text);
	}

	private static IllegalArgumentException invalid(String entry, String reason) {
		return new IllegalArgumentException("Invalid trusted proxy '" + entry + "': " + reason);
	}

	private record Range(InetAddress network, int prefix) {

		boolean matches(byte[] candidate) {
			byte[] bytes = network.getAddress();
			if (candidate.length != bytes.length) {
				return false;
			}
			int fullBytes = prefix / Byte.SIZE;
			for (int i = 0; i < fullBytes; i++) {
				if (candidate[i] != bytes[i]) {
					return false;
				}
			}
			int remainingBits = prefix % Byte.SIZE;
			if (remainingBits == 0) {
				return true;
			}
			int mask = (0xFF << (Byte.SIZE - remainingBits)) & 0xFF;
			return (candidate[fullBytes] & mask) == (bytes[fullBytes] & mask);
		}

		@Override
		public String toString() {
			return network.getHostAddress() + "/" + prefix;
		}

	}

}
