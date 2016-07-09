package org.rsdeob;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.rsdeob.stdlib.cfg.BasicBlock;
import org.rsdeob.stdlib.cfg.ControlFlowGraph;
import org.rsdeob.stdlib.cfg.ControlFlowGraphBuilder;
import org.rsdeob.stdlib.cfg.util.ControlFlowGraphDeobfuscator;
import org.rsdeob.stdlib.cfg.util.GraphUtils;
import org.rsdeob.stdlib.ir.*;
import org.rsdeob.stdlib.ir.header.HeaderStatement;
import org.rsdeob.stdlib.ir.stat.CopyVarStatement;
import org.rsdeob.stdlib.ir.stat.Statement;
import org.rsdeob.stdlib.ir.transform.impl.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.Map.Entry;

public class AnalyticsTest {

	public static boolean debug = true;
	
	public static void main(String[] args) throws Throwable {
		InputStream i = new FileInputStream(new File("res/Test.class"));
		ClassReader cr = new ClassReader(i);
		ClassNode cn = new ClassNode();
		cr.accept(cn, 0);

		Iterator<MethodNode> it = cn.methods.listIterator();
		while(it.hasNext()) {
			MethodNode m = it.next();
			
//			if(!m.toString().equals("a/a/f/a.<init>()V")) {
//				continue;
//			}
//			a/a/f/a.<init>()V
//			a/a/f/a.H(J)La/a/f/o;
//			a/a/f/a.H(La/a/f/o;J)V
			System.out.println("Processing " + m + "\n");
			ControlFlowGraphBuilder builder = new ControlFlowGraphBuilder(m);
			ControlFlowGraph cfg = builder.build();
			ControlFlowGraphDeobfuscator deobber = new ControlFlowGraphDeobfuscator();
			List<BasicBlock> blocks = deobber.deobfuscate(cfg);
			deobber.removeEmptyBlocks(cfg, blocks);
			GraphUtils.naturaliseGraph(cfg, blocks);
			
			
			StatementGenerator gen = new StatementGenerator(cfg);
			gen.init(m.maxLocals);
			gen.createExpressions();
			CodeBody code = gen.buildRoot();
			
//			System.out.println(((CopyVarStatement) code.getAt(11)));
			

			System.out.println(code);
			System.out.println();
			
			StatementGraph sgraph = StatementGraphBuilder.create(cfg);
			GraphUtils.output(m.name, sgraph, code, BootEcx.GRAPH_FOLDER, "-sg");

			DefinitionAnalyser defs = new DefinitionAnalyser(sgraph);
			LivenessAnalyser liveness = new LivenessAnalyser(sgraph);
			UsesAnalyserImpl uses = new UsesAnalyserImpl(code, sgraph, defs);
			CodeAnalytics analytics = new CodeAnalytics(cfg, sgraph, defs, liveness, uses);
			code.registerListener(analytics);

			optimise(code, analytics);
			
			System.out.println("Optimised:");
			System.out.println(code);
			System.out.println();
			System.out.println();
//			for(Statement stmt : code) {
//				System.out.println(stmt);
//				System.out.println(defs.in(stmt));
//				System.out.println(defs.out(stmt));
//				System.out.println();
//			}
		}
	}

	public static void optimise(CodeBody code, CodeAnalytics analytics) {
		Transformer[] transforms = LivenessTest.transforms(code, analytics);
		
		while(true) {
			int change = 0;

			for(Transformer t : transforms) {
				change += t.run();
			}
			
			if(change <= 0) {
				break;
			}
		}
	}
	
	public static void verify_callback(CodeBody code, CodeAnalytics analytics, Statement stmt) {
		code.commit();
		
		CodeBodyConsistencyChecker checker = new CodeBodyConsistencyChecker(code, analytics.sgraph);
		try {
			checker.compute();
		} catch(RuntimeException e) {
			e.printStackTrace();
			System.out.println("Consistency error report:");
			System.out.println("  Statements in code but not in graph:");
			for(Statement g : checker.cFaulty) {
				System.out.println("   " + g);
			}
			System.out.println("  Statements in graph but not in code:");
			for(Statement c : checker.gFaulty) {
				System.out.println("   " + c);
			}
		}
		DefinitionAnalyser d1 = new DefinitionAnalyser(analytics.sgraph);
		LivenessAnalyser l1 = new LivenessAnalyser(analytics.sgraph);
		
		for(Statement s : code) {
			try {
				Map<Local, Set<CopyVarStatement>> din1 = analytics.definitions.in(s);
				Map<Local, Set<CopyVarStatement>> din2 = d1.in(s);
				Map<Local, Set<CopyVarStatement>> dout1 = analytics.definitions.out(s);
				Map<Local, Set<CopyVarStatement>> dout2 = d1.out(s);

				dcheck("DIN", din1, din2, analytics, code, stmt, s);
				dcheck("DOUT", dout1, dout2, analytics, code, stmt, s);
				
				Map<Local, Boolean> lin1 = analytics.liveness.in(s);
				Map<Local, Boolean> lin2 = l1.in(s);
				Map<Local, Boolean> lout1 = analytics.liveness.out(s);
				Map<Local, Boolean> lout2 = l1.out(s);

				lcheck("LIN", lin1, lin2, analytics, code, stmt, s);
				lcheck("LOUT", lout1, lout2, analytics, code, stmt, s);
			} catch(Exception e) {
				if(e instanceof RuntimeException) {
					throw (RuntimeException)e;
				} else {
					throw new RuntimeException(e);
				}
			}
		}
	}
	
	public static void lcheck(String type, Map<Local, Boolean> s1, Map<Local, Boolean> s2, CodeAnalytics analytics, CodeBody code, Statement stmt, Statement cur) throws Exception {
		if(!(cur instanceof HeaderStatement) && !lcheck0(s1, s2)) {
			System.err.println();
			System.err.println(code);
			System.err.println("Fail: " + stmt.getId() + ". " + stmt);
			System.err.println(" Cur: " + cur.getId() + ". " + cur);
			System.err.println("  Actual " + type + "1:");
			if(s1 != null) {
				for(Entry<Local, Boolean> e : s1.entrySet()) {
					System.err.println("    " + e.getKey() + " = " + e.getValue());
				}
			} else {
				System.err.println("   NULL");
			}
			System.err.println("  Should " + type + "2:");
			if(s2 != null) {
				for(Entry<Local, Boolean> e : s2.entrySet()) {
					System.err.println("    " + e.getKey() + " = " + e.getValue());
				}
			} else {
				System.err.println("   NULL");
			}
			GraphUtils.output("failedat", analytics.sgraph, code, BootEcx.GRAPH_FOLDER, "-sg");
			throw new RuntimeException();
		}
	}
	
	public static boolean lcheck0(Map<Local, Boolean> s1, Map<Local, Boolean> s2) {
		if(s1 == null || s2 == null) {
			return false;
		}
		
		Set<Local> keys = new HashSet<>();
		keys.addAll(s1.keySet());
		keys.addAll(s2.keySet());
		
		for(Local key : keys) {
			if(!s1.containsKey(key)) {
				// return false;
			} else if(!s2.containsKey(key)) {
				// return false;
			} else {
				if(s1.get(key).booleanValue() != s2.get(key).booleanValue()) {
					return false;
				}
			}
		}
		
		return true;
	}
	
	public static void dcheck(String type, Map<Local, Set<CopyVarStatement>> s1, Map<Local, Set<CopyVarStatement>> s2, CodeAnalytics analytics, CodeBody code, Statement stmt, Statement cur) throws Exception {
		if(!(s1 == null && s2 == null) && !dcheck0(s1, s2)) {
			System.err.println();
			System.err.println(code);
			System.err.println("Fail: " + stmt.getId() + ". " + stmt);
			System.err.println(" Cur: " + cur.getId() + ". " + cur);
			System.err.println("  Actual " + type + "1:");
			if(s1 != null) {
				for(Entry<Local, Set<CopyVarStatement>> e : s1.entrySet()) {
					System.err.println("    " + e.getKey() + " = " + e.getValue());
				}
			} else {
				System.err.println("   NULL");
			}
			System.err.println("  Should " + type + "2:");
			if(s2 != null) {
				for(Entry<Local, Set<CopyVarStatement>> e : s2.entrySet()) {
					System.err.println("    " + e.getKey() + " = " + e.getValue());
				}
			} else {
				System.err.println("   NULL");
			}
			GraphUtils.output("failedat", analytics.sgraph, code, BootEcx.GRAPH_FOLDER, "-sg");
			throw new RuntimeException();
		}
	}
	
	public static boolean dcheck0(Map<Local, Set<CopyVarStatement>> s1, Map<Local, Set<CopyVarStatement>> s2) {
		if(s1 == null || s2 == null) {
			return false;
		}
		
		Set<Local> keys = new HashSet<>();
		keys.addAll(s1.keySet());
		keys.addAll(s2.keySet());
		
		for(Local key : keys) {
			Set<CopyVarStatement> set1 = s1.get(key);
			Set<CopyVarStatement> set2 = s2.get(key);
			
			if(set1 == null && set2.isEmpty()) {
				continue;
			} else if(set2 == null && set1.isEmpty()) {
				continue;
			}
			
			if(!set1.equals(set2)) {
				for(Local l : keys) {
					if(s1.get(l) == null || s1.get(l).isEmpty()) {
						s1.remove(l);
					}
					if(s2.get(l) == null || s2.get(l).isEmpty()) {
						s2.remove(l);
					}
				}
				System.err.println("[D] mistmatch for " + key);
				return false;
			}
		}
		
		return true;
	}
}