package org.mapleir.ir.code.expr;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.stmt.Statement;
import org.mapleir.stdlib.cfg.util.TabbedStringWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

public class PhiExpression extends Expression {

	private final Map<BasicBlock, Expression> arguments;
	private Type type;
	
	public PhiExpression(Map<BasicBlock, Expression> arguments) {
		super(PHI);
		this.arguments = arguments;
	}
	
	public int getArgumentCount() {
		return arguments.size();
	}
	
	public Set<BasicBlock> getSources() {
		return new HashSet<>(arguments.keySet());
	}
	
	public Map<BasicBlock, Expression> getArguments() {
		return arguments;
	}
	
	public Expression getArgument(BasicBlock b) {
		return arguments.get(b);
	}
	
	public void setArgument(BasicBlock b, Expression e) {
//		if(arguments.containsKey(b)) {
			arguments.put(b, e);
			markDirty();
//		} else {
//			throw new IllegalStateException("phi has a fixed size of " + arguments.size() + ": " + b + ", " + e);
//		}
	}
	
	public void removeArgument(BasicBlock b) {
		arguments.remove(b);
		markDirty();
	}
	
	@Override
	public void onChildUpdated(int ptr) {
		
	}

	@Override
	public Expression copy() {
		Map<BasicBlock, Expression> map = new HashMap<>();
		for(Entry<BasicBlock, Expression> e : arguments.entrySet()) {
			map.put(e.getKey(), e.getValue());
		}
		return new PhiExpression(map);
	}

	@Override
	public Type getType() {
		return type;
	}
	
	public void setType(Type type) {
		this.type = type;
	}

	@Override
	public void toString(TabbedStringWriter printer) {
		printer.print("\u0278{");
		Iterator<Entry<BasicBlock, Expression>> it = arguments.entrySet().iterator();
		while(it.hasNext()) {
			Entry<BasicBlock, Expression> e = it.next();
			
			printer.print(e.getKey().getId());
			printer.print(":");
			e.getValue().toString(printer);
			
			if(it.hasNext()) {
				printer.print(", ");
			}
		}
		printer.print("}");
	}

	@Override
	public void toCode(MethodVisitor visitor, ControlFlowGraph cfg) {
		throw new UnsupportedOperationException("Phi is not executable.");
	}

	@Override
	public boolean canChangeFlow() {
		return false;
	}

	@Override
	public boolean canChangeLogic() {
		return true;
	}

	@Override
	public boolean isAffectedBy(Statement stmt) {
		return false;
	}

	@Override
	public boolean equivalent(Statement s) {
		if(s instanceof PhiExpression) {
			PhiExpression phi = (PhiExpression) s;
			
			Set<BasicBlock> sources = new HashSet<>();
			sources.addAll(arguments.keySet());
			sources.addAll(phi.arguments.keySet());
			
			if(sources.size() != arguments.size()) {
				return false;
			}
			
			for(BasicBlock b : sources) {
				Expression e1 = arguments.get(b);
				Expression e2 = phi.arguments.get(b);
				if(e1 == null || e2 == null) {
					return false;
				}
				if(!e1.equivalent(e2)) {
					return false;
				}
			}
			
			return true;
		}
		return false;
	}
}