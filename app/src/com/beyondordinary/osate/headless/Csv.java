// SPDX-License-Identifier: EPL-2.0
package com.beyondordinary.osate.headless;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.osate.aadl2.instance.SystemInstance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Reads OSATE's FHA CSV into JSON rows keyed by its own column headings. */
final class Csv {

	private Csv() {
	}

	static JsonArray readHazards(Path unused, SystemInstance si) {
		JsonArray rows = new JsonArray();
		try {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(ModelWorkspace.PROJECT);
			project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
			Path base = project.getLocation().toFile().toPath();
			Path fha;
			try (Stream<Path> walk = Files.walk(base)) {
				fha = walk.filter(p -> p.getFileName().toString().contains(si.getName() + "__FHA")
						&& p.toString().endsWith(".csv")).findFirst().orElse(null);
			}
			if (fha == null) return rows;
			List<String> lines = Files.readAllLines(fha, StandardCharsets.UTF_8);
			if (lines.isEmpty()) return rows;
			List<String> header = parse(lines.get(0));
			for (int i = 1; i < lines.size(); i++) {
				if (lines.get(i).isBlank()) continue;
				List<String> cells = parse(lines.get(i));
				JsonObject o = new JsonObject();
				for (int c = 0; c < header.size() && c < cells.size(); c++) {
					String v = cells.get(c).trim();
					if (!v.isEmpty()) o.addProperty(key(header.get(c)), v);
				}
				rows.add(o);
			}
		} catch (Exception e) {
			JsonObject err = new JsonObject();
			err.addProperty("error", "could not read FHA report: " + e);
			rows.add(err);
		}
		return rows;
	}

	private static String key(String heading) {
		return heading.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
	}

	/** Minimal RFC 4180 line parser: commas, double-quoted cells, doubled quotes. */
	static List<String> parse(String line) {
		List<String> cells = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean q = false;
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (q) {
				if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
					cur.append('"');
					i++;
				} else if (ch == '"') {
					q = false;
				} else {
					cur.append(ch);
				}
			} else if (ch == '"') {
				q = true;
			} else if (ch == ',') {
				cells.add(cur.toString());
				cur.setLength(0);
			} else {
				cur.append(ch);
			}
		}
		cells.add(cur.toString());
		return cells;
	}
}
