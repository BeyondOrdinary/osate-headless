// SPDX-License-Identifier: EPL-2.0
package com.beyondordinary.osate.headless;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.osate.aadl2.SystemImplementation;
import org.osate.xtext.aadl2.ui.internal.Aadl2Activator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Injector;

/** Imports a model directory into a fresh AADL project, builds it, and reads markers. */
final class ModelWorkspace {

	static final String PROJECT = "model";
	static final String FOLDER = "packages";

	private ModelWorkspace() {
	}

	static IProject importModel(Path modelDir) throws Exception {
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		IProject project = ws.getRoot().getProject(PROJECT);
		if (project.exists()) project.delete(true, true, new NullProgressMonitor());
		IProjectDescription d = ws.newProjectDescription(PROJECT);
		d.setNatureIds(new String[] { "org.osate.core.aadlnature", "org.eclipse.xtext.ui.shared.xtextNature" });
		ICommand build = d.newCommand();
		build.setBuilderName("org.eclipse.xtext.ui.shared.xtextBuilder");
		d.setBuildSpec(new ICommand[] { build });
		project.create(d, new NullProgressMonitor());
		project.open(new NullProgressMonitor());
		IFolder root = project.getFolder(FOLDER);
		root.create(true, true, new NullProgressMonitor());

		List<Path> files;
		try (Stream<Path> walk = Files.walk(modelDir)) {
			files = walk.filter(p -> p.toString().endsWith(".aadl") && Files.isRegularFile(p)).sorted().toList();
		}
		if (files.isEmpty()) throw new IOException("no .aadl files under " + modelDir);
		for (Path f : files) {
			Path rel = modelDir.relativize(f);
			IFolder parent = root;
			for (int i = 0; i < rel.getNameCount() - 1; i++) {
				parent = parent.getFolder(rel.getName(i).toString());
				if (!parent.exists()) parent.create(true, true, new NullProgressMonitor());
			}
			IFile target = parent.getFile(rel.getFileName().toString());
			try (InputStream in = Files.newInputStream(f)) {
				target.create(in, true, new NullProgressMonitor());
			}
		}
		ws.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
		Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());
		Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, new NullProgressMonitor());
		return project;
	}

	/** Markers on the declarative model (instance=false) or on instance files (instance=true). */
	static JsonObject markers(IProject project, boolean instance) throws CoreException {
		IMarker[] all = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
		int errors = 0, warnings = 0;
		JsonArray list = new JsonArray();
		for (IMarker m : all) {
			boolean isInstance = m.getResource().getName().endsWith(".aaxl2");
			if (isInstance != instance) continue;
			int sev = m.getAttribute(IMarker.SEVERITY, -1);
			String s = sev == IMarker.SEVERITY_ERROR ? "error" : sev == IMarker.SEVERITY_WARNING ? "warning" : "info";
			if (sev == IMarker.SEVERITY_ERROR) errors++;
			if (sev == IMarker.SEVERITY_WARNING) warnings++;
			JsonObject o = new JsonObject();
			o.addProperty("severity", s);
			o.addProperty("file", m.getResource().getProjectRelativePath().toString().replaceFirst("^" + FOLDER + "/", ""));
			o.addProperty("line", m.getAttribute(IMarker.LINE_NUMBER, -1));
			o.addProperty("message", m.getAttribute(IMarker.MESSAGE, ""));
			list.add(o);
		}
		JsonObject o = new JsonObject();
		o.addProperty("errors", errors);
		o.addProperty("warnings", warnings);
		o.add("markers", list);
		return o;
	}

	static ResourceSet resourceSet(IProject project) {
		Injector injector = Aadl2Activator.getInstance().getInjector(Aadl2Activator.ORG_OSATE_XTEXT_AADL2_AADL2);
		return injector.getInstance(IResourceSetProvider.class).get(project);
	}

	/** Loads every .aadl resource of the project into one resource set. */
	static ResourceSet loadAll(IProject project) throws CoreException {
		ResourceSet rs = resourceSet(project);
		project.accept(r -> {
			if (r instanceof IFile f && "aadl".equals(f.getFileExtension())) {
				rs.getResource(URI.createPlatformResourceURI(f.getFullPath().toString(), true), true);
			}
			return true;
		});
		return rs;
	}

	static SystemImplementation findImplementation(IProject project, String qualified) throws CoreException {
		String pkg = qualified.substring(0, qualified.indexOf("::"));
		String name = qualified.substring(qualified.indexOf("::") + 2);
		ResourceSet rs = loadAll(project);
		for (Resource r : List.copyOf(rs.getResources())) {
			for (TreeIterator<EObject> it = r.getAllContents(); it.hasNext();) {
				EObject o = it.next();
				if (o instanceof SystemImplementation si && name.equalsIgnoreCase(si.getName())
						&& si.getElementRoot() != null && pkg.equalsIgnoreCase(si.getElementRoot().getName())) {
					return si;
				}
			}
		}
		return null;
	}

	/** Copies OSATE's own report files (FHA, fault impact) next to result.json. */
	static JsonArray copyReports(IProject project, Path dst) throws Exception {
		JsonArray names = new JsonArray();
		project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		Path base = project.getLocation().toFile().toPath();
		Files.createDirectories(dst);
		try (Stream<Path> walk = Files.walk(base)) {
			for (Path p : walk.filter(Files::isRegularFile)
					.filter(p -> p.toString().contains("/reports/") || p.toString().contains("\\reports\\")).toList()) {
				Files.copy(p, dst.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				names.add("reports/" + p.getFileName());
			}
		}
		return names;
	}
}
