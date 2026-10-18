package com.transferservice.transfer_processing_service.mock.provider;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.transferservice.transfer_processing_service.mock.MockApiException;

/**
 * Simulation plan for one payment, parsed from {@code SIM:<TOKEN>} markers in the narration,
 * e.g. {@code "Invoice SIM:FAIL SIM:CONFLICTING_CALLBACKS"}.
 */
record MockProviderScenario(Set<String> tokens) {

	static final String FAIL = "FAIL";

	static final String PENDING = "PENDING";

	static final String DUPLICATE_CALLBACKS = "DUPLICATE_CALLBACKS";

	static final String DELAYED_CALLBACK = "DELAYED_CALLBACK";

	static final String CONFLICTING_CALLBACKS = "CONFLICTING_CALLBACKS";

	static final String NO_CALLBACK = "NO_CALLBACK";

	static final String EARLY_CALLBACK = "EARLY_CALLBACK";

	static final String SLOW_RESPONSE = "SLOW_RESPONSE";

	private static final Set<String> KNOWN = Set.of(FAIL, PENDING, DUPLICATE_CALLBACKS, DELAYED_CALLBACK,
			CONFLICTING_CALLBACKS, NO_CALLBACK, EARLY_CALLBACK, SLOW_RESPONSE);

	private static final Pattern TOKEN = Pattern.compile("SIM:([A-Z_]+)");

	static MockProviderScenario parse(String narration) {
		Set<String> tokens = new TreeSet<>();
		if (narration != null) {
			Matcher matcher = TOKEN.matcher(narration);
			while (matcher.find()) {
				String token = matcher.group(1);
				if (!KNOWN.contains(token)) {
					throw MockApiException.unprocessable("UNKNOWN_SCENARIO", "Unknown simulation token SIM:" + token);
				}
				tokens.add(token);
			}
		}
		return new MockProviderScenario(tokens);
	}

	boolean has(String token) {
		return this.tokens.contains(token);
	}

	String initialStatus() {
		if (has(PENDING)) {
			return "PROCESSING";
		}
		return has(FAIL) ? "FAILED" : "SUCCEEDED";
	}

	String describe() {
		return this.tokens.isEmpty() ? "SUCCEED" : String.join(",", this.tokens);
	}

}
