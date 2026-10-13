// SPDX-License-Identifier: EPL-2.0
package com.beyondordinary.osate.headless;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Text lints that run before OSATE. They do not replace the compiler; they name
 * the real cause of compile errors that OSATE reports misleadingly.
 *
 * <ul>
 * <li>L1: an AADL reserved word used as an identifier. OSATE reports a parse
 * error ("expecting RULE_ID") and then unrelated unresolved references.</li>
 * <li>L2: a package-qualified classifier on a bus access feature. OSATE 2.20
 * does not resolve it ("Couldn't resolve reference to BusFeatureClassifier"),
 * while the same reference resolves on a subcomponent. A local
 * {@code renames bus Pkg::Name;} works around it. See upstream-issues/01.</li>
 * </ul>
 */
final class Lints {

	/** AADL v2.2 reserved words (SAE AS5506C, 15.x). */
	static final Set<String> RESERVED = Set.of("aadlboolean", "aadlinteger", "aadlreal", "aadlstring", "abstract",
			"access", "all", "and", "annex", "applies", "binding", "bus", "calls", "classifier", "compute",
			"connections", "constant", "data", "delta", "device", "end", "enumeration", "event", "extends", "false",
			"feature", "features", "flow", "flows", "group", "implementation", "in", "inherit", "initial", "inverse",
			"is", "list", "memory", "mode", "modes", "none", "not", "of", "or", "out", "package", "parameter", "path",
			"port", "private", "process", "processor", "properties", "property", "prototypes", "provides", "public",
			"range", "record", "reference", "refined", "renames", "requires", "self", "set", "sink", "source",
			"subcomponents", "subprogram", "system", "thread", "to", "true", "type", "units", "virtual", "with");

	private static final Pattern DECL = Pattern.compile("^\\s*(\\w+)\\s*:(?!:)");
	private static final Pattern IMPL_NAME = Pattern.compile("\\bimplementation\\s+[\\w:]+\\.(\\w+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern BUS_ACCESS = Pattern.compile(
			"\\b(requires|provides)\\s+bus\\s+access\\s+(\\w+)::(\\w+)", Pattern.CASE_INSENSITIVE);

	private Lints() {
	}

	static JsonArray run(Path modelDir) throws IOException {
		JsonArray out = new JsonArray();
		List<Path> files;
		try (Stream<Path> walk = Files.walk(modelDir)) {
			files = walk.filter(p -> p.toString().endsWith(".aadl")).sorted().toList();
		}
		for (Path f : files) {
			String rel = modelDir.relativize(f).toString();
			List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
			boolean inAnnex = false;
			for (int i = 0; i < lines.size(); i++) {
				String line = stripComment(lines.get(i));
				// Annex sublanguages have their own keywords; skip their bodies.
				if (line.contains("{**")) inAnnex = true;
				if (inAnnex) {
					if (line.contains("**}")) inAnnex = false;
					continue;
				}
				Matcher d = DECL.matcher(line);
				if (d.find() && RESERVED.contains(d.group(1).toLowerCase(Locale.ROOT))) {
					out.add(finding("L1", "error", rel, i + 1, "'" + d.group(1)
							+ "' is an AADL reserved word and cannot name a declaration. OSATE reports this as a parse error and may then report unrelated unresolved references."));
				}
				Matcher m = IMPL_NAME.matcher(line);
				if (m.find() && RESERVED.contains(m.group(1).toLowerCase(Locale.ROOT))) {
					out.add(finding("L1", "error", rel, i + 1, "implementation name '" + m.group(1)
							+ "' is an AADL reserved word"));
				}
				Matcher b = BUS_ACCESS.matcher(line);
				if (b.find()) {
					out.add(finding("L2", "warning", rel, i + 1, "package-qualified bus access classifier "
							+ b.group(2) + "::" + b.group(3)
							+ " does not resolve in OSATE 2.20. Declare 'renames bus " + b.group(2) + "::" + b.group(3)
							+ ";' in the package and use the local name."));
				}
			}
		}
		return out;
	}

	private static String stripComment(String line) {
		int i = line.indexOf("--");
		return i < 0 ? line : line.substring(0, i);
	}

	private static JsonObject finding(String code, String severity, String file, int line, String message) {
		JsonObject o = new JsonObject();
		o.addProperty("code", code);
		o.addProperty("severity", severity);
		o.addProperty("file", file);
		o.addProperty("line", line);
		o.addProperty("message", message);
		return o;
	}
}
