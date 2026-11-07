package com.example.api_gateway.ratelimit;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TrustedProxiesTests {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"10.0.0.7                 | 10.0.0.7         | true",
			"10.0.0.7                 | 10.0.0.8         | false",
			"10.0.0.7/32              | 10.0.0.7         | true",
			"10.0.0.7/32              | 10.0.0.6         | false",
			"192.168.1.0/24           | 192.168.1.255    | true",
			"192.168.1.0/24           | 192.168.2.1      | false",
			"172.16.0.0/12            | 172.31.255.254   | true",
			"172.16.0.0/12            | 172.32.0.1       | false",
			"192.168.1.77/24          | 192.168.1.3      | true",
			"0.0.0.0/0                | 203.0.113.9      | true",
			"0.0.0.0/0                | 2001:db8::1      | false",
			"2001:db8:1:2::/64        | 2001:db8:1:2::99 | true",
			"2001:db8:1:2::/64        | 2001:db8:1:3::1  | false",
			"::1                      | ::1              | true",
			"::/0                     | 2001:db8::1      | true",
			"::/0                     | 10.0.0.1         | false",
			"10.0.0.0/24              | ::ffff:10.0.0.5  | true",
			"::ffff:10.0.0.5          | 10.0.0.5         | true" })
	void matchesAddressesInsideTheRange(String entries, String address, boolean expected) {
		TrustedProxies proxies = TrustedProxies.valueOf(entries);

		assertThat(proxies.contains(InetAddress.ofLiteral(address))).isEqualTo(expected);
	}

	@Test
	void acceptsSeveralEntriesWithWhitespace() {
		TrustedProxies proxies = TrustedProxies.valueOf("  10.0.0.0/8 ,192.168.1.10,\t2001:db8::/32 , ");

		assertThat(proxies.contains(InetAddress.ofLiteral("10.200.0.1"))).isTrue();
		assertThat(proxies.contains(InetAddress.ofLiteral("192.168.1.10"))).isTrue();
		assertThat(proxies.contains(InetAddress.ofLiteral("2001:db8:ffff::1"))).isTrue();
		assertThat(proxies.contains(InetAddress.ofLiteral("192.168.1.11"))).isFalse();
		assertThat(proxies).hasToString("[10.0.0.0/8, 192.168.1.10/32, 2001:db8:0:0:0:0:0:0/32]");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", " , ,", "\t" })
	void emptyTrustsNothing(String entries) {
		TrustedProxies proxies = TrustedProxies.valueOf(entries);

		assertThat(proxies.isEmpty()).isTrue();
		assertThat(proxies.contains(InetAddress.ofLiteral("127.0.0.1"))).isFalse();
		assertThat(proxies.contains(InetAddress.ofLiteral("::1"))).isFalse();
	}

	@Test
	void noneTrustsNothing() {
		assertThat(TrustedProxies.none().isEmpty()).isTrue();
		assertThat(TrustedProxies.none().contains(InetAddress.ofLiteral("10.0.0.1"))).isFalse();
		assertThat(TrustedProxies.none()).hasToString("[]");
	}

	@Test
	void unknownAddressIsNotTrusted() {
		assertThat(TrustedProxies.valueOf("0.0.0.0/0").contains(null)).isFalse();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"10.0.0.0/33         | 'Invalid trusted proxy ''10.0.0.0/33'': prefix length must be 0-32'",
			"2001:db8::/129      | 'Invalid trusted proxy ''2001:db8::/129'': prefix length must be 0-128'",
			"10.0.0.0/-1         | 'Invalid trusted proxy ''10.0.0.0/-1'': prefix length must be 0-32'",
			"10.0.0.0/           | 'Invalid trusted proxy ''10.0.0.0/'': prefix length must be 0-32'",
			"10.0.0.0/8/8        | 'Invalid trusted proxy ''10.0.0.0/8/8'': prefix length must be 0-32'",
			"abc                 | 'Invalid trusted proxy ''abc'': not an IP address'",
			"localhost           | 'Invalid trusted proxy ''localhost'': not an IP address'",
			"10.0.0.256          | 'Invalid trusted proxy ''10.0.0.256'': not an IP address'",
			"10.1                | 'Invalid trusted proxy ''10.1'': not an IP address'",
			"2001:db8::zz        | 'Invalid trusted proxy ''2001:db8::zz'': not an IP address'",
			"/24                 | 'Invalid trusted proxy ''/24'': not an IP address'" })
	void rejectsInvalidEntryAndNamesIt(String entry, String message) {
		assertThatIllegalArgumentException().isThrownBy(() -> TrustedProxies.valueOf("10.0.0.1, " + entry))
			.withMessage(message);
	}

}
