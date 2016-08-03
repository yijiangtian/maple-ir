package org.mapleir.stdlib.ir.gen.interference;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.mapleir.stdlib.cfg.BasicBlock;
import org.mapleir.stdlib.cfg.ControlFlowGraph;
import org.mapleir.stdlib.collections.NullPermeableHashMap;
import org.mapleir.stdlib.collections.SetCreator;
import org.mapleir.stdlib.ir.expr.Expression;
import org.mapleir.stdlib.ir.expr.PhiExpression;
import org.mapleir.stdlib.ir.expr.VarExpression;
import org.mapleir.stdlib.ir.locals.Local;
import org.mapleir.stdlib.ir.stat.CopyVarStatement;
import org.mapleir.stdlib.ir.stat.Statement;
import org.mapleir.stdlib.ir.transform.Liveness;

public class InterferenceGraphBuilder {

	private final Map<Local, ColourableNode> localMap;
	private final InterferenceGraph graph;
	
	private InterferenceGraphBuilder() {
		localMap = new HashMap<>();
		graph = new InterferenceGraph();
	}
	
	private ColourableNode getVertex(Local l) {
		return localMap.get(l);
	}
	
	private ColourableNode getVertexIf(Local l) {
		ColourableNode n = getVertex(l);
		if(n == null) {
			n = new ColourableNode(l, 0);
			graph.addVertex(n);
		}
		return n;
	}
	
	private InterferenceGraph build(Map<Local, Set<Local>> map) {
		for(Entry<Local, Set<Local>> e : map.entrySet()) {
			Local l = e.getKey();
			Set<Local> set = e.getValue();
			
			ColourableNode n1 = getVertexIf(l);
			
			for(Local l2 : set) {
				ColourableNode n2 = getVertexIf(l2);
				InterferenceEdge edge = new InterferenceEdge(n1, n2);
				graph.addEdge(n1, edge);
			}
		}
		return graph;
	}
	
	public static InterferenceGraph build(ControlFlowGraph cfg, Liveness<BasicBlock> liveness) {
		NullPermeableHashMap<Local, Set<Local>> interfere = new NullPermeableHashMap<>(new SetCreator<>());
		
		for(BasicBlock b : cfg.vertices()) {
			Set<Local> out = liveness.out(b);
			
			for(Statement stmt : b.getStatements()) {
				if(stmt instanceof CopyVarStatement) {
					CopyVarStatement copy = (CopyVarStatement) stmt;
					Local def = copy.getVariable().getLocal();
					Expression e = copy.getExpression();
					
					for(Local o : out) {
						interfere.getNonNull(o).add(def);
						interfere.getNonNull(def).add(o);
					}
					
					if(e instanceof PhiExpression) {
						
					} else if(!(e instanceof VarExpression)) {
						for(Statement s : Statement.enumerate(e)) {
							if(s instanceof VarExpression) {
								VarExpression v = (VarExpression) s;
							}
						}
					}
				}
			}
		}
		
		
		return new InterferenceGraphBuilder().build(interfere);
	}
}