package org.mapleir.ir.algorithms;

import java.util.*;

import org.mapleir.app.service.TypeUtils;
import org.mapleir.flowgraph.ExceptionRange;
import org.mapleir.flowgraph.edges.FlowEdge;
import org.mapleir.flowgraph.edges.FlowEdges;
import org.mapleir.flowgraph.edges.ImmediateEdge;
import org.mapleir.flowgraph.edges.UnconditionalJumpEdge;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.stmt.UnconditionalJumpStmt;
import org.mapleir.stdlib.collections.graph.FastDirectedGraph;
import org.mapleir.stdlib.collections.graph.FastGraph;
import org.mapleir.stdlib.collections.graph.FastGraphEdge;
import org.mapleir.stdlib.collections.graph.FastGraphEdgeImpl;
import org.mapleir.stdlib.collections.graph.FastGraphVertex;
import org.mapleir.stdlib.collections.graph.algorithms.SimpleDfs;
import org.mapleir.stdlib.collections.graph.algorithms.TarjanSCC;
import org.mapleir.stdlib.util.IndexedList;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

public class ControlFlowGraphDumper {
	private final ControlFlowGraph cfg;
	private final MethodNode m;
	
	private IndexedList<BasicBlock> order;
	private LabelNode terminalLabel; // synthetic last label for malformed ranges
	
	public ControlFlowGraphDumper(ControlFlowGraph cfg, MethodNode m) {
		this.cfg = cfg;
		this.m = m;
	}
	
	public void dump() {
		// Clear methodnode
		m.instructions.removeAll(true);
		m.tryCatchBlocks.clear();
		m.visitCode();
		for (BasicBlock b : cfg.vertices()) {
			b.resetLabel();
		}

		// Linearize
		linearize();
		
		// Fix edges
		naturalise();
		
		// Sanity check linearization
		verifyOrdering();

		// Dump code
		for (BasicBlock b : order) {
			m.visitLabel(b.getLabel());
			for (Stmt stmt : b) {
				stmt.toCode(m, null);
			}
		}
		terminalLabel = new LabelNode();
		m.visitLabel(terminalLabel.getLabel());

		// Dump ranges
		for (ExceptionRange<BasicBlock> er : cfg.getRanges()) {
			dumpRange(er);
		}
		
		// Sanity check
		verifyRanges();
		
		m.visitEnd();
	}
	
	private void linearize() {
		if (cfg.getEntries().size() != 1)
			throw new IllegalStateException("CFG doesn't have exactly 1 entry");
		BasicBlock entry = cfg.getEntries().iterator().next();
		
		// Build bundle graph
		Map<BasicBlock, BlockBundle> bundles = new HashMap<>();
		Map<BlockBundle, List<BlockBundle>> bunches = new HashMap<>();
		
		// Build bundles
		List<BasicBlock> topoorder = new SimpleDfs<>(cfg, entry, SimpleDfs.TOPO).getTopoOrder();
		for (BasicBlock b : topoorder) {
			if (bundles.containsKey(b)) // Already in a bundle
				continue;
			
			if (b.getIncomingImmediateEdge() != null) // Look for heads of bundles only
				continue;
			
			BlockBundle bundle = new BlockBundle();
			while (b != null) {
				bundle.add(b);
				bundles.put(b, bundle);
				b = b.getImmediate();
			}
			
			List<BlockBundle> bunch = new ArrayList<>();
			bunch.add(bundle);
			bunches.put(bundle, bunch);
		}
		
		// Group bundles by exception ranges
		for (ExceptionRange<BasicBlock> range : cfg.getRanges()) {
			BlockBundle prevBundle = null;
			for (BasicBlock b : range.getNodes()) {
				BlockBundle curBundle = bundles.get(b);
				if (prevBundle == null) {
					prevBundle = curBundle;
					continue;
				}
				if (curBundle != prevBundle) {
					List<BlockBundle> bunchA = bunches.get(prevBundle);
					List<BlockBundle> bunchB = bunches.get(curBundle);
					if (bunchA != bunchB) {
						bunchA.addAll(bunchB);
						for (BlockBundle bundle : bunchB) {
							bunches.put(bundle, bunchA);
						}
					}
					prevBundle = curBundle;
				}
			}
		}
		
		// Rebuild bundles
		bundles.clear();
		for (Map.Entry<BlockBundle, List<BlockBundle>> e : bunches.entrySet()) {
			BlockBundle bundle = e.getKey();
			if (bundles.containsKey(bundle.getFirst()))
				continue;
			BlockBundle bunch = new BlockBundle();
			e.getValue().forEach(bunch::addAll);
			for (BasicBlock b : bunch)
				bundles.put(b, bunch);
		}
		
		// Connect bundle graph
		BundleGraph bundleGraph = new BundleGraph();
		BlockBundle entryBundle = bundles.get(entry);
		bundleGraph.addVertex(entryBundle);
		for (BasicBlock b : topoorder) {
			for (FlowEdge<BasicBlock> e : cfg.getEdges(b)) {
				if (e instanceof ImmediateEdge)
					continue;
				BlockBundle src = bundles.get(b);
				bundleGraph.addEdge(src, new FastGraphEdgeImpl<>(src, bundles.get(e.dst())));
			}
		}
		
		// Linearize & flatten
		order = new IndexedList<>();
		Set<BlockBundle> bundlesSet = new HashSet<>(bundles.values()); // for efficiency
		ControlFlowGraphDumper.linearize(bundlesSet, bundleGraph, entryBundle).forEach(order::addAll);
	}
	
	// Recursively apply Tarjan's SCC algorithm
	private static List<BlockBundle> linearize(Collection<BlockBundle> bundles, BundleGraph fullGraph, BlockBundle entryBundle) {
		BundleGraph subgraph = fullGraph.inducedSubgraph(bundles);
		
		// Find SCCs
		TarjanSCC<BlockBundle> sccComputor = new TarjanSCC<>(subgraph);
		sccComputor.search(entryBundle);
		for(BlockBundle b : bundles) {
			if(sccComputor.low(b) == -1) {
				sccComputor.search(b);
			}
		}
		
		// Flatten
		List<BlockBundle> order = new ArrayList<>();
		List<List<BlockBundle>> components = sccComputor.getComponents();
		if (components.size() == 1)
			order.addAll(components.get(0));
		else for (List<BlockBundle> scc : components) // Recurse
			order.addAll(linearize(scc, subgraph, chooseEntry(subgraph, scc)));
		return order;
	}
	
	private static BlockBundle chooseEntry(BundleGraph graph, List<BlockBundle> scc) {
		Set<BlockBundle> sccSet = new HashSet<>(scc);
		Set<BlockBundle> candidates = new HashSet<>(scc);
		candidates.removeIf(bundle -> { // No incoming edges from within the SCC.
			for (FastGraphEdge<BlockBundle> e : graph.getReverseEdges(bundle)) {
				if (sccSet.contains(e.src()))
					return true;
			}
			return false;
		});
		if (candidates.isEmpty())
			return scc.get(0);
		return candidates.iterator().next();
	}

	private void naturalise() {
		for (int i = 0; i < order.size(); i++) {
			BasicBlock b = order.get(i);
			for (FlowEdge<BasicBlock> e : new HashSet<>(cfg.getEdges(b))) {
				BasicBlock dst = e.dst();
				if (e instanceof ImmediateEdge && order.indexOf(dst) != i + 1) {
					// Fix immediates
					b.add(new UnconditionalJumpStmt(dst));
					cfg.removeEdge(b, e);
					cfg.addEdge(b, new UnconditionalJumpEdge<>(b, dst));
				} else if (e instanceof UnconditionalJumpEdge && order.indexOf(dst) == i + 1) {
					// Remove extraneous gotos
					for (ListIterator<Stmt> it = b.listIterator(b.size()); it.hasPrevious(); ) {
						if (it.previous() instanceof UnconditionalJumpStmt) {
							it.remove();
							break;
						}
					}
					cfg.removeEdge(b, e);
					cfg.addEdge(b, new ImmediateEdge<>(b, dst));
				}
			}
		}
	}

	private void verifyOrdering() {
		ListIterator<BasicBlock> it = order.listIterator();
		while(it.hasNext()) {
			BasicBlock b = it.next();
			
			for(FlowEdge<BasicBlock> e: cfg.getEdges(b)) {
				if(e.getType() == FlowEdges.IMMEDIATE) {
					if(it.hasNext()) {
						BasicBlock n = it.next();
						it.previous();
						
						if(n != e.dst()) {
							throw new IllegalStateException("Illegal flow " + e + " > " + n);
						}
					} else {
						throw new IllegalStateException("Trailing " + e);
					}
				}
			}
		}
	}

	private void dumpRange(ExceptionRange<BasicBlock> er) {
		// Determine exception type
		Type type;
		Set<Type> typeSet = er.getTypes();
		if (typeSet.size() != 1) {
			// TODO: find base exception
			type = TypeUtils.THROWABLE;
		} else {
			type = typeSet.iterator().next();
		}
		
		final Label handler = er.getHandler().getLabel();
		List<BasicBlock> range = er.get();
		range.sort(Comparator.comparing(order::indexOf));
		
		Label start;
		int rangeIdx = -1, orderIdx;
		do {
			if (++rangeIdx == range.size()) {
				System.err.println("[warn] range is absent: " + m);
				return;
			}
			BasicBlock b = range.get(rangeIdx);
			orderIdx = order.indexOf(b);
			start = b.getLabel();
		} while (orderIdx == -1);
		
		for (;;) {
			// check for endpoints
			if (orderIdx + 1 == order.size()) { // end of method
				m.visitTryCatchBlock(start, terminalLabel.getLabel(), handler, type.getInternalName());
				break;
			} else if (rangeIdx + 1 == range.size()) { // end of range
				Label end = order.get(orderIdx + 1).getLabel();
				m.visitTryCatchBlock(start, end, handler, type.getInternalName());
				break;
			}
			
			// check for discontinuity
			BasicBlock nextBlock = range.get(rangeIdx + 1);
			int nextOrderIdx = order.indexOf(nextBlock);
			if (nextOrderIdx - orderIdx > 1) { // blocks in-between, end the handler and begin anew
				System.err.println("[warn] Had to split up a range: " + m);
				Label end = order.get(orderIdx + 1).getLabel();
				m.visitTryCatchBlock(start, end, handler, type.getInternalName());
				start = nextBlock.getLabel();
			}

			// next
			rangeIdx++;
			if (nextOrderIdx != -1)
				orderIdx = nextOrderIdx;
		}
	}
	
	private void verifyRanges() {
		for (TryCatchBlockNode tc : m.tryCatchBlocks) {
			int start = -1, end = -1, handler = -1;
			for (int i = 0; i < m.instructions.size(); i++) {
				AbstractInsnNode ain = m.instructions.get(i);
				if (!(ain instanceof LabelNode))
					continue;
				Label l = ((LabelNode) ain).getLabel();
				if (l == tc.start.getLabel())
					start = i;
				if (l == tc.end.getLabel()) {
					if (start == -1)
						throw new IllegalStateException("Try block end before start " + m);
					end = i;
				}
				if (l == tc.handler.getLabel()) {
					handler = i;
				}
			}
			if (start == -1 || end == -1 || handler == -1)
				throw new IllegalStateException("Try/catch endpoints missing: " + start + " " + end + " " + handler + m);
		}
	}
	
	private static class BundleGraph extends FastDirectedGraph<BlockBundle, FastGraphEdge<BlockBundle>> {
		@Override
		public boolean excavate(BlockBundle basicBlocks) {
			throw new UnsupportedOperationException();
		}
		
		@Override
		public boolean jam(BlockBundle pred, BlockBundle succ, BlockBundle basicBlocks) {
			throw new UnsupportedOperationException();
		}
		
		@Override
		public FastGraphEdge<BlockBundle> clone(FastGraphEdge<BlockBundle> edge, BlockBundle oldN, BlockBundle newN) {
			throw new UnsupportedOperationException();
		}
		
		@Override
		public FastGraphEdge<BlockBundle> invert(FastGraphEdge<BlockBundle> edge) {
			throw new UnsupportedOperationException();
		}
		
		@Override
		public FastGraph<BlockBundle, FastGraphEdge<BlockBundle>> copy() {
			throw new UnsupportedOperationException();
		}
		
		// todo: move up to FastGraph!
		public BundleGraph inducedSubgraph(Collection<BlockBundle> vertices) {
			BundleGraph subgraph = new BundleGraph();
			for (BlockBundle n : vertices) {
				subgraph.addVertex(n);
				for (FastGraphEdge<BlockBundle> e : getEdges(n)) {
					if (vertices.contains(e.dst()))
						subgraph.addEdge(n, e);
				}
			}
			return subgraph;
		}
	}
	
	@SuppressWarnings("serial")
	private static class BlockBundle extends ArrayList<BasicBlock> implements FastGraphVertex {
		private BasicBlock first = null;
		
		private BasicBlock getFirst() {
			if (first == null)
				first = get(0);
			return first;
		}
		
		@Override
		public String getDisplayName() {
			return getFirst().getDisplayName();
		}
		
		@Override
		public int getNumericId() {
			return getFirst().getNumericId();
		}
		
		@Override
		public String toString() {
			StringBuilder s = new StringBuilder();
			for (Iterator<BasicBlock> it = this.iterator(); it.hasNext(); ) {
				BasicBlock b = it.next();
				s.append(b.getDisplayName());
				if (it.hasNext())
					s.append("->");
			}
			return s.toString();
		}
		
		@Override
		public int hashCode() {
			return getFirst().hashCode();
		}
		
		@Override
		public boolean equals(Object o) {
			if (!(o instanceof BlockBundle))
				return false;
			return ((BlockBundle) o).getFirst().equals(getFirst());
		}
	}
}
