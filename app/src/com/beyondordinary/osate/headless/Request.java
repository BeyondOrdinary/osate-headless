// SPDX-License-Identifier: EPL-2.0
package com.beyondordinary.osate.headless;

import java.util.List;

/** Request file, schema osate_headless.request.v1. Field names match the JSON keys. */
public final class Request {

	public String schema;
	/** Directory holding the .aadl files (searched recursively). */
	public String model_dir;
	/** Root system implementation, Package::Type.impl. */
	public String root;
	/** Any of "compile", "instantiate", "emv2". Default: all three. */
	public List<String> analyses;
	/** Fault-tree start points. */
	public List<FtaStart> fta_starts;
	/** "MILSTD882" (default), "ARP4761" or "EMV2". */
	public String hazard_format;
	/** Optional property whose string values are requirement IDs, e.g. "My_Props::Requirement_IDs". */
	public String requirement_property;
	/** Optional: the IDs the caller expects to be traced. */
	public List<String> requirement_ids;

	public static final class FtaStart {
		/** Instance path below the root, segments separated by "/". Empty = the root. */
		public String component;
		/** "state NAME" or "outgoing propagation on PORT{TYPE}". */
		public String start;
	}

	boolean wants(String analysis) {
		return analyses == null || analyses.isEmpty() || analyses.contains(analysis);
	}

	void validate() {
		if (schema != null && !schema.equals("osate_headless.request.v1")) {
			throw new IllegalArgumentException("unsupported schema " + schema);
		}
		if (model_dir == null || model_dir.isBlank()) throw new IllegalArgumentException("model_dir is required");
		if (root == null || !root.contains("::") || !root.contains(".")) {
			throw new IllegalArgumentException("root must be Package::Type.impl");
		}
		if (hazard_format == null) hazard_format = "MILSTD882";
		if (!List.of("MILSTD882", "ARP4761", "EMV2").contains(hazard_format)) {
			throw new IllegalArgumentException("hazard_format must be MILSTD882, ARP4761 or EMV2");
		}
		if (fta_starts != null) {
			for (FtaStart s : fta_starts) {
				if (s.component == null) s.component = "";
				if (s.start == null || !(s.start.startsWith("state ") || s.start.startsWith("outgoing propagation on "))) {
					throw new IllegalArgumentException("fta start must be 'state NAME' or 'outgoing propagation on PORT{TYPE}'");
				}
			}
		}
	}
}
