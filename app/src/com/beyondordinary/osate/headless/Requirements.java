// SPDX-License-Identifier: EPL-2.0
package com.beyondordinary.osate.headless;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.osate.aadl2.ContainedNamedElement;
import org.osate.aadl2.ContainmentPathElement;
import org.osate.aadl2.Element;
import org.osate.aadl2.ListValue;
import org.osate.aadl2.ModalPropertyValue;
import org.osate.aadl2.NamedElement;
import org.osate.aadl2.PropertyAssociation;
import org.osate.aadl2.PropertyExpression;
import org.osate.aadl2.StringLiteral;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Requirement traceability from a string-list property (for example
 * {@code My_Props::Requirement_IDs => ("SR-01", "SR-02")}): which declarative
 * model elements carry each ID.
 */
final class Requirements {

	private Requirements() {
	}

	static JsonObject trace(IProject project, String property, List<String> expected) throws Exception {
		ResourceSet rs = ModelWorkspace.loadAll(project);
		Map<String, TreeSet<String>> byId = new TreeMap<>();
		for (Resource r : List.copyOf(rs.getResources())) {
			if (!r.getURI().toString().endsWith(".aadl")) continue;
			for (TreeIterator<EObject> it = r.getAllContents(); it.hasNext();) {
				if (!(it.next() instanceof PropertyAssociation pa) || pa.getProperty() == null) continue;
				if (!property.equalsIgnoreCase(pa.getProperty().getQualifiedName())) continue;
				List<String> ids = new ArrayList<>();
				for (ModalPropertyValue mpv : pa.getOwnedValues()) collect(mpv.getOwnedValue(), ids);
				for (String target : targets(pa)) {
					for (String id : ids) byId.computeIfAbsent(id, k -> new TreeSet<>()).add(target);
				}
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("property", property);
		JsonObject map = new JsonObject();
		for (var e : byId.entrySet()) {
			JsonArray a = new JsonArray();
			e.getValue().forEach(a::add);
			map.add(e.getKey(), a);
		}
		o.add("elements_by_id", map);
		if (expected != null) {
			JsonArray untraced = new JsonArray(), undefined = new JsonArray();
			for (String id : new TreeSet<>(expected)) if (!byId.containsKey(id)) untraced.add(id);
			for (String id : byId.keySet()) if (!expected.contains(id)) undefined.add(id);
			o.add("untraced", untraced);
			o.add("undefined", undefined);
		}
		return o;
	}

	private static void collect(PropertyExpression v, List<String> ids) {
		if (v instanceof StringLiteral s) ids.add(s.getValue());
		else if (v instanceof ListValue l) {
			for (PropertyExpression e : l.getOwnedListElements()) collect(e, ids);
		}
	}

	private static List<String> targets(PropertyAssociation pa) {
		String owner = name(pa.getOwner());
		List<String> out = new ArrayList<>();
		if (pa.getAppliesTos().isEmpty()) {
			out.add(owner);
			return out;
		}
		for (ContainedNamedElement cne : pa.getAppliesTos()) {
			List<String> segs = new ArrayList<>();
			for (ContainmentPathElement p : cne.getContainmentPathElements()) {
				if (p.getNamedElement() != null) segs.add(p.getNamedElement().getName());
			}
			out.add(owner + "." + String.join(".", segs));
		}
		return out;
	}

	private static String name(Element e) {
		for (Element x = e; x != null; x = x.getOwner()) {
			if (x instanceof NamedElement ne && ne.getQualifiedName() != null) return ne.getQualifiedName();
		}
		return "?";
	}
}
