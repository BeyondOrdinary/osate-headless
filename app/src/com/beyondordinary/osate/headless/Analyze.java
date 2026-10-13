// SPDX-License-Identifier: EPL-2.0
package com.beyondordinary.osate.headless;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.osate.aadl2.SystemImplementation;
import org.osate.aadl2.instance.SystemInstance;
import org.osate.aadl2.instantiation.InstantiateModel;
import org.osate.aadl2.instance.ComponentInstance;
import org.osate.aadl2.instance.EndToEndFlowInstance;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.osgi.framework.Bundle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Headless OSATE analysis: one request file in, one result file out.
 *
 * <pre>
 *   java ... -jar plugins/org.eclipse.equinox.launcher_*.jar -nosplash \
 *     -application com.beyondordinary.osate.headless.analyze -data WORKSPACE \
 *     -request request.json -out OUTDIR
 * </pre>
 *
 * Exit status: 0 when the analysis ran to a result (any status except "failed"),
 * 1 when the status is "failed", 2 for usage or tooling errors. The status and
 * the reasons are always in OUTDIR/result.json; the exit status is a summary.
 */
public class Analyze implements IApplication {

	static final String APP_VERSION = "0.1.0";
	static final String RESULT_SCHEMA = "osate_headless.result.v1";

	@Override
	public Object start(IApplicationContext context) throws Exception {
		String[] args = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
		String requestPath = null, outDir = null;
		for (int i = 0; i < args.length; i++) {
			if ("-request".equals(args[i]) && i + 1 < args.length) requestPath = args[++i];
			else if ("-out".equals(args[i]) && i + 1 < args.length) outDir = args[++i];
		}
		if (requestPath == null || outDir == null) {
			System.err.println("usage: -request REQUEST.json -out OUTDIR");
			return 2;
		}
		Path out = Path.of(outDir);
		Files.createDirectories(out);
		Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

		Request req;
		try (Reader r = Files.newBufferedReader(Path.of(requestPath), StandardCharsets.UTF_8)) {
			req = gson.fromJson(r, Request.class);
			req.validate();
		} catch (Exception e) {
			System.err.println("bad request: " + e.getMessage());
			return 2;
		}

		Status status = new Status();
		JsonObject result = new JsonObject();
		result.addProperty("schema", RESULT_SCHEMA);
		result.add("tool", toolInfo());
		result.addProperty("root", req.root);
		try {
			run(req, out, result, status);
		} catch (Throwable t) {
			status.fail("exception: " + t);
			t.printStackTrace();
		}
		result.addProperty("status", status.state());
		result.add("notes", status.notesJson());
		// Keep status near the top for humans: rebuild with a stable order.
		JsonObject ordered = new JsonObject();
		for (String k : List.of("schema", "status", "notes", "tool", "root")) {
			if (result.has(k)) ordered.add(k, result.get(k));
		}
		for (var e : result.entrySet()) {
			if (!ordered.has(e.getKey())) ordered.add(e.getKey(), e.getValue());
		}
		try (Writer w = Files.newBufferedWriter(out.resolve("result.json"), StandardCharsets.UTF_8)) {
			gson.toJson(ordered, w);
		}
		System.out.println("status: " + status.state() + " (" + out.resolve("result.json") + ")");
		return "failed".equals(status.state()) ? 1 : IApplication.EXIT_OK;
	}

	@Override
	public void stop() {
	}

	private static void run(Request req, Path out, JsonObject result, Status status) throws Exception {
		Path modelDir = Path.of(req.model_dir);

		// Text lints run first: they explain compile errors that OSATE reports misleadingly.
		JsonArray lints = Lints.run(modelDir);
		result.add("lints", lints);

		IProject project = ModelWorkspace.importModel(modelDir);
		JsonObject compile = ModelWorkspace.markers(project, false);
		result.add("compile", compile);
		if (compile.get("errors").getAsInt() > 0) {
			status.fail("compile errors: " + compile.get("errors").getAsInt());
			for (var l : lints) {
				status.note("lint " + l.getAsJsonObject().get("code").getAsString() + ": "
						+ l.getAsJsonObject().get("message").getAsString());
			}
			return;
		}
		if (!req.wants("instantiate") && !req.wants("emv2")) {
			return;
		}

		SystemImplementation impl = ModelWorkspace.findImplementation(project, req.root);
		if (impl == null) {
			status.fail("root not found: " + req.root);
			return;
		}
		SystemInstance si = InstantiateModel.buildInstanceModelFile(impl);
		if (si == null) {
			status.fail("instantiation failed");
			return;
		}
		result.add("instance", describe(si));
		JsonObject instanceMarkers = ModelWorkspace.markers(project, true);
		result.add("instance_markers", instanceMarkers);
		if (instanceMarkers.get("errors").getAsInt() > 0) {
			status.fail("instance model has errors: " + instanceMarkers.get("errors").getAsInt());
			return;
		}

		if (req.wants("emv2")) {
			JsonObject emv2 = new Emv2Analysis(si, req, out, status).run();
			JsonArray reports = ModelWorkspace.copyReports(project, out.resolve("reports"));
			emv2.add("reports", reports);
			result.add("emv2", emv2);
		}
		if (req.requirement_property != null && !req.requirement_property.isBlank()) {
			result.add("requirements", Requirements.trace(project, req.requirement_property, req.requirement_ids));
		}
	}

	private static JsonObject describe(SystemInstance si) {
		int comps = 0;
		for (TreeIterator<EObject> it = si.eAllContents(); it.hasNext();) {
			if (it.next() instanceof ComponentInstance) comps++;
		}
		JsonObject o = new JsonObject();
		o.addProperty("name", si.getName());
		o.addProperty("components", comps);
		o.addProperty("connections", si.getAllConnectionInstances().size());
		o.addProperty("system_operation_modes", si.getSystemOperationModes().size());
		JsonArray flows = new JsonArray();
		for (EndToEndFlowInstance e : si.getEndToEndFlows()) {
			JsonObject f = new JsonObject();
			f.addProperty("name", e.getName());
			f.addProperty("elements", e.getFlowElements().size());
			flows.add(f);
		}
		o.add("end_to_end_flows", flows);
		return o;
	}

	private static JsonObject toolInfo() {
		JsonObject t = new JsonObject();
		t.addProperty("app", "com.beyondordinary.osate.headless");
		t.addProperty("app_version", APP_VERSION);
		for (String b : new String[] { "org.osate.aadl2", "org.osate.xtext.aadl2.errormodel",
				"org.osate.aadl2.errormodel.faulttree.generation" }) {
			Bundle bundle = Platform.getBundle(b);
			t.addProperty(b, bundle == null ? "absent" : bundle.getVersion().toString());
		}
		t.addProperty("java", System.getProperty("java.version"));
		t.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
		return t;
	}

	/** Verification state, from strongest failure to clean. See README "Verification state". */
	static final class Status {
		private boolean failed, unverified, undeclared;
		private final List<String> notes = new ArrayList<>();

		void fail(String why) { failed = true; notes.add("failed: " + why); }
		void unverified(String why) { unverified = true; notes.add("unverified: " + why); }
		void undeclared(String why) { undeclared = true; notes.add("undeclared: " + why); }
		void note(String what) { notes.add(what); }

		String state() {
			if (failed) return "failed";
			if (unverified) return "unverified";
			if (undeclared) return "undeclared";
			return "verified";
		}

		JsonArray notesJson() {
			JsonArray a = new JsonArray();
			notes.forEach(a::add);
			return a;
		}
	}
}
