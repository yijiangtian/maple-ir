//package org.mapleir.ir.cfg.builder;
//
//import org.mapleir.ir.DVBTest;
//import org.mapleir.ir.Test;
//import org.mapleir.ir.analysis.SSALocalAccess;
//import org.mapleir.ir.cfg.BasicBlock;
//
//public class OptimisationPass extends ControlFlowGraphBuilder.BuilderPass {
//
//	public OptimisationPass(ControlFlowGraphBuilder builder) {
//		super(builder);
//	}
//
//	// TODO: optimise
//	private Optimiser[] findOptimisers() {
//		SSALocalAccess a = builder.localAccess;
//		return new Optimiser[] {
//			new Propagator(builder, a),
//			new Aggregator(builder, a)
//		};
//	}
//	
//	@Override
//	public void run() {
//		Test.temp = true;
//		DVBTest.FLAG = true;
//		builder.localAccess = new SSALocalAccess(builder.graph);
//		Optimiser[] opt = findOptimisers();
//		
//		int changes;
//		
//		do {
//			changes = 0;
//			
//			for(Optimiser o : opt) {
//				changes += o.run();
//			}
//			
//			for(BasicBlock b : builder.graph.vertices()) {
//				for(Optimiser o : opt) {
//					changes += o.run(b);
//				}
//			}
//		} while(changes > 0);
//	}
//	
//	public static abstract class Optimiser {
//		protected final ControlFlowGraphBuilder builder;
//		protected final SSALocalAccess localAccess;
//		
//		public Optimiser(ControlFlowGraphBuilder builder, SSALocalAccess localAccess) {
//			this.builder = builder;
//			this.localAccess = localAccess;
//		}
//		
//		public int run() {
//			return 0;
//		}
//		
//		public abstract int run(BasicBlock b);
//	}
//}