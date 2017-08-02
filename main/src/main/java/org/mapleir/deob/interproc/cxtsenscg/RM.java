package org.mapleir.deob.interproc.cxtsenscg;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.mapleir.deob.interproc.geompa.util.ChunkedQueue;
import org.mapleir.deob.interproc.geompa.util.QueueReader;
import org.mapleir.stdlib.collections.graph.FastGraphEdge;
import org.objectweb.asm.tree.MethodNode;

public class RM {

	private final ContextInsensitiveCallGraph callGraph;
	private final Iterator<MethodNode> nodeSource;

	private final Set<MethodNode> visited = new HashSet<>();

	private final ChunkedQueue<MethodNode> reachables;
	private final QueueReader<MethodNode> reachableListener; // external reader
	private final QueueReader<MethodNode> unprocessedMethods; // our reader

	public RM(ContextInsensitiveCallGraph callGraph, Iterator<MethodNode> entryPoints) {
		this.callGraph = callGraph;
		nodeSource = callGraph.getNodeListener();

		reachables = new ChunkedQueue<>();
		reachableListener = reachables.reader();

		unprocessedMethods = reachables.reader();

		addMethods(entryPoints);
	}

	private void addMethods(Iterator<? extends MethodNode> methods) {
		while (methods.hasNext())
			addMethod(methods.next());
	}

	private void addMethod(MethodNode m) {
		if (visited.add(m)) {
			// just discovered
			reachables.add(m);
		}
	}

	public void update() {
		while (nodeSource.hasNext()) {
			MethodNode dst = nodeSource.next();
			addMethod(dst);

			if (callGraph.getReverseEdges(dst).size() == 0) {
				throw new RuntimeException(dst.toString());
			}
		}

		while (unprocessedMethods.hasNext()) {
			MethodNode m = unprocessedMethods.next();

			for (FastGraphEdge<MethodNode> e : callGraph.getEdges(m)) {
				addMethod(e.dst);
			}
		}
	}

	public QueueReader<MethodNode> getReachableListener() {
		return reachableListener;
	}
}