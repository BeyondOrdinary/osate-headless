// SPDX-License-Identifier: EPL-2.0
package com.beyondordinary.osate.headless;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.osate.aadl2.AnnexSubclause;
import org.osate.aadl2.Classifier;
import org.osate.aadl2.ComponentImplementation;
import org.osate.aadl2.DefaultAnnexSubclause;
import org.osate.aadl2.NamedElement;
import org.osate.aadl2.errormodel.FaultTree.Event;
import org.osate.aadl2.errormodel.FaultTree.EventType;
import org.osate.aadl2.errormodel.FaultTree.FaultTree;
import org.osate.aadl2.errormodel.FaultTree.LogicOperation;
import org.osate.aadl2.errormodel.PropagationGraph.PropagationGraph;
import org.osate.aadl2.errormodel.PropagationGraph.PropagationGraphPath;
import org.osate.aadl2.errormodel.PropagationGraph.PropagationPathEnd;
import org.osate.aadl2.errormodel.PropagationGraph.util.Util;
import org.osate.aadl2.errormodel.analysis.PropagateErrorSources;
import org.osate.aadl2.errormodel.analysis.fha.FHAReport;
import org.osate.aadl2.errormodel.faulttree.generation.CreateFTAModel;
import org.osate.aadl2.instance.ComponentInstance;
import org.osate.aadl2.instance.ConnectionInstance;
import org.osate.aadl2.instance.InstanceObject;
import org.osate.aadl2.instance.SystemInstance;
import org.osate.xtext.aadl2.errormodel.errorModel.ErrorBehaviorEvent;
import org.osate.xtext.aadl2.errormodel.errorModel.ErrorBehaviorState;
import org.osate.xtext.aadl2.errormodel.errorModel.ErrorPropagation;
import org.osate.xtext.aadl2.errormodel.errorModel.ErrorSource;
import org.osate.xtext.aadl2.errormodel.errorModel.ErrorTypes;
import org.osate.xtext.aadl2.errormodel.errorModel.OutgoingPropagationCondition;
import org.osate.xtext.aadl2.errormodel.errorModel.TypeSet;
import org.osate.xtext.aadl2.errormodel.errorModel.TypeToken;
import org.osate.xtext.aadl2.errormodel.util.EMV2TypeSetUtil;
import org.osate.xtext.aadl2.errormodel.util.EMV2Util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * EMV2 analyses with guards against OSATE results that look valid but are not.
 *
 * <ul>
 * <li>G1 census: EMV2 declared but nothing parsed or no propagation graph.</li>
 * <li>G2 (upstream-issues/02): fault-tree node names are lowercased, so a
 * state and an event whose names differ only in case merge.</li>
 * <li>G3 (upstream-issues/03): an error source on a connection or a bus
 * binding that no propagation path reaches is silently ignored.</li>
 * <li>G4 (upstream-issues/04): the tree for an outgoing propagation omits
 * some unconditional state propagations; the state trees are unioned in.</li>
 * <li>G5: an undeveloped event in a cut set means an incoming propagation
 * with no modelled source, so the tree is incomplete.</li>
 * </ul>
 */
final class Emv2Analysis {

	private static final Pattern OUT_START = Pattern.compile("outgoing propagation on\\s+(\\w+)\\s*\\{\\s*(\\w+)\\s*\\}");

	private final SystemInstance si;
	private final Request req;
	private final Path out;
	private final Analyze.Status status;
	private final List<ComponentInstance> withEmv2 = new ArrayList<>();
	private PropagationGraph graph;

	Emv2Analysis(SystemInstance si, Request req, Path out, Analyze.Status status) {
		this.si = si;
		this.req = req;
		this.out = out;
		this.status = status;
	}

	JsonObject run() {
		JsonObject o = new JsonObject();
		o.add("census", census());
		if (withEmv2.isEmpty()) {
			status.undeclared("no EMV2 subclause on any component instance");
			return o;
		}
		graph = Util.generatePropagationGraph(si, false);
		o.add("graph", graphSummary());
		o.add("sources", sources());
		o.add("fta_targets", targets());
		o.add("name_collisions", nameCollisions());
		faultImpact();
		fha();
		o.add("hazards", Csv.readHazards(out, si));
		o.add("fault_trees", faultTrees(o.getAsJsonArray("name_collisions")));
		return o;
	}

	// ---------------------------------------------------------------- census (G1)

	private JsonObject census() {
		int declared = 0, parsed = 0;
		JsonArray comps = new JsonArray();
		for (TreeIterator<EObject> it = si.eAllContents(); it.hasNext();) {
			if (!(it.next() instanceof ComponentInstance ci) || ci.getComponentClassifier() == null) continue;
			List<Classifier> cls = new ArrayList<>(ci.getComponentClassifier().getSelfPlusAllExtended());
			if (ci.getComponentClassifier() instanceof ComponentImplementation impl && impl.getType() != null) {
				cls.addAll(impl.getType().getSelfPlusAllExtended());
			}
			boolean any = false;
			for (Classifier c : cls) {
				for (AnnexSubclause a : c.getOwnedAnnexSubclauses()) {
					if (!"EMV2".equalsIgnoreCase(a.getName())) continue;
					declared++;
					Object p = a instanceof DefaultAnnexSubclause d ? d.getParsedAnnexSubclause() : a;
					if (p != null) {
						parsed++;
						any = true;
					}
				}
			}
			if (any) {
				withEmv2.add(ci);
				comps.add(ci.getComponentInstancePath());
			}
		}
		if (declared > parsed) {
			status.unverified("G1: " + (declared - parsed) + " EMV2 subclause(s) declared but not parsed");
		}
		JsonObject o = new JsonObject();
		o.addProperty("subclauses_declared", declared);
		o.addProperty("subclauses_parsed", parsed);
		o.add("components", comps);
		return o;
	}

	// ---------------------------------------------------------------- graph

	private JsonObject graphSummary() {
		int unhandled = 0;
		JsonArray paths = new JsonArray();
		for (PropagationGraphPath p : graph.getPropagationGraphPaths()) {
			JsonObject j = new JsonObject();
			j.addProperty("src", end(p.getPathSrc()));
			j.addProperty("dst", end(p.getPathDst()));
			if (p.getConnection() != null) j.addProperty("connection", p.getConnection().getName());
			ErrorPropagation s = p.getPathSrc().getErrorPropagation(), d = p.getPathDst().getErrorPropagation();
			if (s != null && d != null) {
				boolean handled = EMV2TypeSetUtil.contains(d.getTypeSet(), s.getTypeSet());
				j.addProperty("handled", handled);
				if (!handled) unhandled++;
			}
			paths.add(j);
		}
		JsonObject o = new JsonObject();
		o.addProperty("components", graph.getComponents().size());
		o.addProperty("paths", graph.getPropagationGraphPaths().size());
		o.addProperty("unhandled", unhandled);
		o.add("propagation_paths", paths);
		if (graph.getPropagationGraphPaths().isEmpty()) {
			// Only suspicious when two EMV2-bearing components are actually connected.
			int linked = 0;
			for (ConnectionInstance c : si.getAllConnectionInstances()) {
				if (c.getSource() == null || c.getDestination() == null) continue;
				if (hasEmv2Above(c.getSource().getComponentInstance()) && hasEmv2Above(c.getDestination().getComponentInstance())) linked++;
			}
			if (linked > 0) {
				status.unverified("G1: " + linked + " connection(s) join components that declare EMV2, but the propagation graph has no paths."
						+ " Typical cause: containment only (sinks, 'not out propagation') and no out propagation on the sending side");
			} else {
				status.note("no propagation paths (no connection joins two components that declare EMV2)");
			}
		}
		return o;
	}

	private boolean hasEmv2Above(ComponentInstance ci) {
		for (ComponentInstance c = ci; c != null; c = c.getContainingComponentInstance()) {
			if (withEmv2.contains(c)) return true;
		}
		return false;
	}

	private static String end(PropagationPathEnd e) {
		String c = e.getConnectionInstance() != null ? "connection " + e.getConnectionInstance().getName()
				: e.getComponentInstance() == null ? "?" : e.getComponentInstance().getComponentInstancePath();
		ErrorPropagation ep = e.getErrorPropagation();
		return ep == null ? c : c + "." + EMV2Util.getPrintName(ep) + EMV2Util.getPrintName(ep.getTypeSet());
	}

	// ---------------------------------------------------------------- sources (G3)

	private JsonArray sources() {
		JsonArray a = new JsonArray();
		for (ComponentInstance ci : withEmv2) {
			for (ErrorSource es : EMV2Util.getAllErrorSources(ci)) {
				NamedElement el = es.getSourceModelElement();
				boolean reachesComponent = false, reachesAny = false;
				for (PropagationGraphPath p : graph.getPropagationGraphPaths()) {
					if (p.getPathSrc().getComponentInstance() != ci) continue;
					if (el instanceof ErrorPropagation ep && p.getPathSrc().getErrorPropagation() != ep) continue;
					reachesAny = true;
					if (p.getPathDst().getErrorPropagation() != null) reachesComponent = true;
				}
				JsonObject j = new JsonObject();
				j.addProperty("component", ci.getComponentInstancePath());
				j.addProperty("name", es.getName());
				j.addProperty("element", el == null ? "all" : EMV2Util.getPrintName(el));
				j.addProperty("types", es.getTypeTokenConstraint() == null ? "" : EMV2Util.getPrintName(es.getTypeTokenConstraint()));
				boolean bindings = el != null && "bindings".equalsIgnoreCase(EMV2Util.getPrintName(el));
				if (bindings && reachesAny && !reachesComponent) {
					j.addProperty("reached", false);
					status.unverified("G3: binding source " + ci.getComponentInstancePath() + "." + es.getName()
							+ " reaches connections but no receiving component: the propagation stops at the connection");
				} else {
					// An unreached port source is legitimate when the port has no outgoing connection.
					j.addProperty("reached", reachesComponent);
				}
				a.add(j);
			}
		}
		Map<ErrorSource, List<ConnectionInstance>> conn = new LinkedHashMap<>();
		for (ConnectionInstance ci : si.getAllConnectionInstances()) {
			ErrorSource es = EMV2Util.findConnectionErrorSourceForConnection(ci);
			if (es != null) conn.computeIfAbsent(es, k -> new ArrayList<>()).add(ci);
		}
		for (var e : conn.entrySet()) {
			boolean reached = false;
			for (PropagationGraphPath p : graph.getPropagationGraphPaths()) {
				if (e.getValue().contains(p.getConnection())) reached = true;
			}
			JsonObject j = new JsonObject();
			j.addProperty("connection_source", e.getKey().getName());
			j.addProperty("types", e.getKey().getTypeTokenConstraint() == null ? ""
					: EMV2Util.getPrintName(e.getKey().getTypeTokenConstraint()));
			JsonArray insts = new JsonArray();
			e.getValue().forEach(c -> insts.add(c.getName()));
			j.add("connection_instances", insts);
			j.addProperty("reached", reached);
			if (!reached) {
				status.unverified("G3: connection error source " + e.getKey().getName()
						+ " has no propagation path: the sending component declares no EMV2 out propagation on that connection, so the source is ignored");
			}
			a.add(j);
		}
		return a;
	}

	// ---------------------------------------------------------------- FTA targets

	private JsonArray targets() {
		JsonArray a = new JsonArray();
		for (ComponentInstance ci : withEmv2) {
			JsonObject j = new JsonObject();
			j.addProperty("component", relPath(ci));
			JsonArray starts = new JsonArray();
			for (ErrorBehaviorState s : EMV2Util.getAllErrorBehaviorStates(ci)) {
				starts.add("state " + s.getName());
			}
			for (ErrorPropagation ep : EMV2Util.getAllOutgoingErrorPropagations(ci.getComponentClassifier())) {
				if (ep.isNot() || ep.getTypeSet() == null) continue;
				for (String t : typeNames(ep.getTypeSet())) {
					starts.add("outgoing propagation on " + EMV2Util.getPrintName(ep) + "{" + t + "}");
				}
			}
			j.add("starts", starts);
			if (!starts.isEmpty()) a.add(j);
		}
		return a;
	}

	private static List<String> typeNames(TypeSet ts) {
		List<String> names = new ArrayList<>();
		for (TypeToken tt : ts.getTypeTokens()) {
			for (ErrorTypes et : tt.getType()) names.add(et.getName());
		}
		return names;
	}

	// ---------------------------------------------------------------- name collisions (G2)

	private JsonArray nameCollisions() {
		JsonArray a = new JsonArray();
		Set<String> seen = new HashSet<>();
		for (ComponentInstance ci : withEmv2) {
			Map<String, String> byLower = new HashMap<>();
			List<String[]> clashes = new ArrayList<>();
			Collection<ErrorBehaviorState> states = EMV2Util.getAllErrorBehaviorStates(ci);
			Collection<ErrorBehaviorEvent> events = EMV2Util.getAllErrorBehaviorEvents(ci);
			for (ErrorBehaviorState s : states) byLower.put(s.getName().toLowerCase(Locale.ROOT), "state " + s.getName());
			for (ErrorBehaviorEvent e : events) {
				String prior = byLower.get(e.getName().toLowerCase(Locale.ROOT));
				if (prior != null) clashes.add(new String[] { prior, "event " + e.getName() });
			}
			for (String[] c : clashes) {
				String key = ci.getComponentClassifier().getQualifiedName() + c[0] + c[1];
				if (!seen.add(key)) continue;
				JsonObject j = new JsonObject();
				j.addProperty("component", ci.getComponentInstancePath());
				j.addProperty("first", c[0]);
				j.addProperty("second", c[1]);
				a.add(j);
				status.unverified("G2: " + ci.getComponentInstancePath() + " has " + c[0] + " and " + c[1]
						+ ", which become one fault-tree node (names are lowercased); trees through them are wrong");
			}
		}
		return a;
	}

	// ---------------------------------------------------------------- OSATE reports

	private void faultImpact() {
		PropagateErrorSources fia = new PropagateErrorSources("FaultImpact", si);
		fia.reportImpactHeading();
		fia.reportTableHeading();
		for (ComponentInstance ci : fia.getSubcomponents()) fia.startErrorFlows(ci);
		fia.saveReport();
	}

	private void fha() {
		FHAReport.HazardFormat f = switch (req.hazard_format) {
		case "ARP4761" -> FHAReport.HazardFormat.ARP4761;
		case "EMV2" -> FHAReport.HazardFormat.EMV2;
		default -> FHAReport.HazardFormat.MILSTD882;
		};
		new FHAReport(f).doFHAReport(si);
	}

	// ---------------------------------------------------------------- fault trees (G4, G5)

	private JsonArray faultTrees(JsonArray collisions) {
		JsonArray a = new JsonArray();
		if (req.fta_starts == null) return a;
		for (Request.FtaStart s : req.fta_starts) {
			JsonObject j = new JsonObject();
			j.addProperty("component", s.component);
			j.addProperty("start", s.start);
			ComponentInstance ci = resolve(s.component);
			if (ci == null) {
				j.addProperty("error", "no component instance at '" + s.component + "'");
				status.unverified("fault tree " + s.component + " | " + s.start + ": component not found");
				a.add(j);
				continue;
			}
			FaultTree tree = CreateFTAModel.createFaultTree(ci, s.start);
			if (tree == null || tree.getRoot() == null) {
				j.addProperty("error", "OSATE produced no tree" + (tree != null && tree.getMessage() != null ? ": " + tree.getMessage() : ""));
				status.unverified("fault tree " + s.component + " | " + s.start + ": no tree produced");
				a.add(j);
				continue;
			}
			j.add("tree", eventJson(tree.getRoot()));
			List<List<JsonObject>> sets = cutSets(ci, s.start);
			JsonArray notes = new JsonArray();

			// G4: union in the state trees for unconditional state -> this propagation.
			Matcher m = OUT_START.matcher(s.start);
			if (m.find()) {
				Set<String> states = unconditionalStates(ci, m.group(1), m.group(2));
				List<List<JsonObject>> added = new ArrayList<>();
				for (String st : states) {
					for (List<JsonObject> cs : cutSets(ci, "state " + st)) {
						if (!covered(cs, sets) && !covered(cs, added)) added.add(cs);
					}
				}
				if (!added.isEmpty()) {
					notes.add("G4: OSATE's tree omitted cut sets reachable through unconditional propagations from state(s) "
							+ states + "; unioned from the state trees");
					status.note("G4 applied to " + s.component + " | " + s.start);
					sets.addAll(added);
					sets = minimize(sets);
				}
			}
			// G5: undeveloped leaves.
			Set<String> undeveloped = new TreeSet<>();
			for (List<JsonObject> cs : sets) {
				for (JsonObject e : cs) {
					if ("Undeveloped".equals(e.get("kind").getAsString())) undeveloped.add(e.get("id").getAsString());
				}
			}
			if (!undeveloped.isEmpty()) {
				notes.add("G5: undeveloped events (incoming propagation with no modelled source): " + undeveloped);
				status.unverified("G5: " + s.component + " | " + s.start + " has undeveloped events " + undeveloped);
			}
			if (collisions.size() > 0) notes.add("G2: name collisions present; see emv2.name_collisions");

			JsonArray cut = new JsonArray();
			int minOrder = Integer.MAX_VALUE;
			for (List<JsonObject> cs : sets) {
				JsonObject c = new JsonObject();
				c.addProperty("order", cs.size());
				JsonArray ev = new JsonArray();
				cs.forEach(ev::add);
				c.add("events", ev);
				cut.add(c);
				minOrder = Math.min(minOrder, cs.size());
			}
			j.add("cut_sets", cut);
			j.addProperty("min_order", sets.isEmpty() ? 0 : minOrder);
			j.add("notes", notes);
			a.add(j);
		}
		return a;
	}

	private ComponentInstance resolve(String path) {
		ComponentInstance ci = si;
		if (path == null || path.isBlank()) return ci;
		for (String seg : path.split("/")) {
			ComponentInstance next = null;
			for (ComponentInstance c : ci.getComponentInstances()) {
				if (c.getName().equalsIgnoreCase(seg)) next = c;
			}
			if (next == null) return null;
			ci = next;
		}
		return ci;
	}

	private String relPath(ComponentInstance ci) {
		List<String> segs = new ArrayList<>();
		for (ComponentInstance c = ci; c != null && c != si; c = c.getContainingComponentInstance()) segs.add(0, c.getName());
		return String.join("/", segs);
	}

	/** States S with an unconditional "S -[ ]-> port{type}" (or all propagations). */
	private static Set<String> unconditionalStates(ComponentInstance ci, String port, String type) {
		Set<String> states = new LinkedHashSet<>();
		for (OutgoingPropagationCondition opc : EMV2Util.getAllOutgoingPropagationConditions(ci)) {
			if (opc.getCondition() != null || opc.isAllStates() || opc.getState() == null) continue;
			boolean portMatch = opc.isAllPropagations()
					|| (opc.getOutgoing() != null && EMV2Util.getPrintName(opc.getOutgoing()).equalsIgnoreCase(port));
			boolean typeMatch = opc.getTypeToken() == null || typeNames(opc.getTypeToken()).stream().anyMatch(type::equalsIgnoreCase);
			if (portMatch && typeMatch) states.add(opc.getState().getName());
		}
		return states;
	}

	private static List<List<JsonObject>> cutSets(ComponentInstance ci, String start) {
		List<List<JsonObject>> sets = new ArrayList<>();
		FaultTree mcs = CreateFTAModel.createMinimalCutSet(ci, start);
		if (mcs == null || mcs.getRoot() == null) return sets;
		Event root = mcs.getRoot();
		if (root.getSubEvents().isEmpty()) {
			sets.add(new ArrayList<>(List.of(leaf(root))));
		} else if (root.getSubEventLogic() == LogicOperation.AND) {
			sets.add(leaves(root));
		} else {
			for (Event c : root.getSubEvents()) sets.add(c.getSubEvents().isEmpty() ? new ArrayList<>(List.of(leaf(c))) : leaves(c));
		}
		return minimize(sets);
	}

	private static List<JsonObject> leaves(Event e) {
		List<JsonObject> l = new ArrayList<>();
		for (Event s : e.getSubEvents()) {
			if (s.getSubEvents().isEmpty()) l.add(leaf(s));
			else l.addAll(leaves(s));
		}
		return l;
	}

	private static JsonObject leaf(Event e) {
		JsonObject j = new JsonObject();
		j.addProperty("id", e.getName());
		j.addProperty("kind", e.getType() == null ? "" : kind(e.getType()));
		EObject inst = e.getRelatedInstanceObject();
		if (inst instanceof ConnectionInstance c) j.addProperty("instance", "connection " + c.getName());
		else if (inst instanceof InstanceObject io) j.addProperty("instance", io.getInstanceObjectPath());
		if (e.getRelatedEMV2Object() instanceof NamedElement ne) j.addProperty("element", EMV2Util.getPrintName(ne));
		if (e.getRelatedErrorType() instanceof NamedElement t) j.addProperty("error_type", t.getName());
		else if (e.getRelatedErrorType() instanceof TypeSet ts) j.addProperty("error_type", EMV2Util.getPrintName(ts));
		return j;
	}

	private static String kind(EventType t) {
		return switch (t) {
		case BASIC -> "Basic";
		case UNDEVELOPED -> "Undeveloped";
		case INTERMEDIATE -> "Intermediate";
		case EXTERNAL -> "External";
		default -> t.getName();
		};
	}

	private static JsonObject eventJson(Event e) {
		JsonObject j = new JsonObject();
		j.addProperty("id", e.getName());
		j.addProperty("kind", e.getType() == null ? "" : kind(e.getType()));
		if (!e.getSubEvents().isEmpty()) {
			j.addProperty("logic", e.getSubEventLogic() == null ? "" : e.getSubEventLogic().getName());
			JsonArray subs = new JsonArray();
			for (Event s : e.getSubEvents()) subs.add(eventJson(s));
			j.add("children", subs);
		}
		if (e.getMessage() != null && !e.getMessage().isEmpty()) j.addProperty("message", e.getMessage());
		return j;
	}

	private static Set<String> ids(List<JsonObject> cs) {
		Set<String> s = new TreeSet<>();
		for (JsonObject e : cs) s.add(e.get("id").getAsString());
		return s;
	}

	/** True when some set in {@code sets} is a subset of {@code cs} (so cs adds nothing). */
	private static boolean covered(List<JsonObject> cs, List<List<JsonObject>> sets) {
		Set<String> c = ids(cs);
		for (List<JsonObject> s : sets) {
			if (c.containsAll(ids(s))) return true;
		}
		return false;
	}

	private static List<List<JsonObject>> minimize(List<List<JsonObject>> sets) {
		List<List<JsonObject>> out = new ArrayList<>();
		Set<Set<String>> kept = new HashSet<>();
		List<List<JsonObject>> sorted = new ArrayList<>(sets);
		sorted.sort((x, y) -> Integer.compare(x.size(), y.size()));
		for (List<JsonObject> s : sorted) {
			Set<String> ids = ids(s);
			boolean superset = false;
			for (Set<String> k : kept) {
				if (ids.containsAll(k)) superset = true;
			}
			if (!superset) {
				kept.add(ids);
				out.add(s);
			}
		}
		return out;
	}
}
