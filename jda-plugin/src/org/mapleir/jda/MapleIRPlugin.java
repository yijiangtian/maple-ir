package org.mapleir.jda;

import org.mapleir.IRCallTracer;
import org.mapleir.deobimpl2.ConcreteStaticInvocationPass;
import org.mapleir.deobimpl2.ConstantExpressionEvaluatorPass;
import org.mapleir.deobimpl2.ConstantExpressionReorderPass;
import org.mapleir.deobimpl2.ConstantParameterPass;
import org.mapleir.deobimpl2.DeadCodeEliminationPass;
import org.mapleir.deobimpl2.FieldRSADecryptionPass;
import org.mapleir.ir.ControlFlowGraphDumper;
import org.mapleir.ir.cfg.BoissinotDestructor;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.cfg.builder.ControlFlowGraphBuilder;
import org.mapleir.ir.code.Expr;
import org.mapleir.stdlib.IContext;
import org.mapleir.stdlib.call.CallTracer;
import org.mapleir.stdlib.deob.IPass;
import org.mapleir.stdlib.deob.PassGroup;
import org.mapleir.stdlib.klass.InvocationResolver;
import org.mapleir.stdlib.klass.library.ApplicationClassSource;
import org.mapleir.stdlib.klass.library.InstalledRuntimeClassSource;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import the.bytecode.club.jda.FileContainer;
import the.bytecode.club.jda.api.Plugin;
import the.bytecode.club.jda.decompilers.Decompiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MapleIRPlugin implements Plugin {
	public static Decompiler MAPLEIR = new IRDecompiler();
	
	public MapleIRPlugin() {
		System.out.println("MapleIR plugin loaded");
	}
	
	public static void main(String[] args) {
		new MapleIRPlugin();
	}
	
	@Override
	public int onGUILoad() {
		cfgs = new HashMap<>();
		return 0;
	}
	
	@Override
	public int onExit() {
		return 0;
	}
	
	private void section(String s) {
		System.out.println(s);
	}
	
	public static Map<MethodNode, ControlFlowGraph> cfgs;
	
	@Override
	public int onAddFile(FileContainer fileContainer) {
		ApplicationClassSource app = new ApplicationClassSource(fileContainer.name, fileContainer.getClasses());
		InstalledRuntimeClassSource jre = new InstalledRuntimeClassSource(app);
		
		section("Building jar class hierarchy.");
		app.addLibraries(jre);
		
		section("Initialising context.");
		
		InvocationResolver resolver = new InvocationResolver(app);
		IContext cxt = new IContext() {
			
			@Override
			public ControlFlowGraph getIR(MethodNode m) {
				if(cfgs.containsKey(m)) {
					return cfgs.get(m);
				} else {
					ControlFlowGraph cfg = ControlFlowGraphBuilder.build(m);
					cfgs.put(m, cfg);
					return cfg;
				}
			}
			
			@Override
			public Set<MethodNode> getActiveMethods() {
				return cfgs.keySet();
			}
			
			@Override
			public InvocationResolver getInvocationResolver() {
				return resolver;
			}
			
			@Override
			public ApplicationClassSource getApplication() {
				return app;
			}
		};
		
		section("Expanding callgraph and generating cfgs.");
		CallTracer tracer = new IRCallTracer(cxt) {
			@Override
			protected void processedInvocation(MethodNode caller, MethodNode callee, Expr call) {
						/* the cfgs are generated by calling IContext.getIR()
						 * in IRCallTracer.traceImpl(). */
			}
		};
		for(ClassNode cn : app.iterate())  {
			for(MethodNode m : cn.methods) {
				tracer.trace(m);
			}
		}
		
		section("Preparing to transform.");
		
		PassGroup masterGroup = new PassGroup("MasterController");
		for(IPass p : getTransformationPasses()) {
			masterGroup.add(p);
		}
		run(cxt, masterGroup);
		
		section("Retranslating SSA IR to standard flavour.");
		for(Map.Entry<MethodNode, ControlFlowGraph> e : cfgs.entrySet()) {
			MethodNode mn = e.getKey();
			ControlFlowGraph cfg = e.getValue();
			
			BoissinotDestructor.leaveSSA(cfg);
			cfg.getLocals().realloc(cfg);
			ControlFlowGraphDumper.dump(cfg, mn);
		}
		
		return 0;
	}
	
	private static void run(IContext cxt, PassGroup group) {
		group.accept(cxt, null, new ArrayList<>());
	}
	
	private static IPass[] getTransformationPasses() {
		return new IPass[] {
//				new CallgraphPruningPass(),
				new ConcreteStaticInvocationPass(),
//				new MethodRenamerPass(),
//				new ClassRenamerPass(),
//				new FieldRenamerPass(),
//				new PassGroup("Interprocedural Optimisations")
//					.add(new ConstantParameterPass())
				new ConstantExpressionReorderPass(),
				new FieldRSADecryptionPass(),
				new PassGroup("Interprocedural Optimisations")
					.add(new ConstantParameterPass())
					.add(new ConstantExpressionEvaluatorPass())
					.add(new DeadCodeEliminationPass())
				
		};
	}
}
