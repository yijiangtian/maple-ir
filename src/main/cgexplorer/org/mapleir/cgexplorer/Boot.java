package org.mapleir.cgexplorer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.mapleir.IRCallTracer;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.cfg.builder.ControlFlowGraphBuilder;
import org.mapleir.ir.code.Expr;
import org.mapleir.stdlib.IContext;
import org.mapleir.stdlib.call.CallTracer;
import org.mapleir.stdlib.klass.InvocationResolver;
import org.mapleir.stdlib.klass.library.ApplicationClassSource;
import org.mapleir.stdlib.klass.library.InstalledRuntimeClassSource;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.topdank.byteengineer.commons.data.JarInfo;
import org.topdank.byteio.in.SingleJarDownloader;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

public class Boot {

	final Map<MethodNode, Set<MethodNode>> callers = new HashMap<>();
	final ApplicationClassSource app;
	
	Boot(ApplicationClassSource app) {
		this.app = app;
	}
	
	public static void main(String[] args) throws IOException {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
				| UnsupportedLookAndFeelException e) {
			e.printStackTrace();
		}
		
		File f = new File("res/gamepack133.jar");
		
		SingleJarDownloader<ClassNode> dl = new SingleJarDownloader<>(new JarInfo(f));
		dl.download();

		ApplicationClassSource app = new ApplicationClassSource(f.getName().substring(0, f.getName().length() - 4), dl.getJarContents().getClassContents());
		InstalledRuntimeClassSource jre = new InstalledRuntimeClassSource(app);
		app.addLibraries(jre);
		
		Boot b = new Boot(app);
		
		InvocationResolver resolver = new InvocationResolver(app);
		Map<MethodNode, ControlFlowGraph> cfgs = new HashMap<>();
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
		
		CallTracer tracer = new IRCallTracer(cxt) {
			@Override
			protected void processedInvocation(MethodNode caller, MethodNode callee, Expr call) {
				/* the cfgs are generated by calling Icontext.getCFGS().getIR()
				 * in IRCallTracer.traceImpl(). */
				
				if(b.callers.containsKey(callee)) {
					b.callers.get(callee).add(caller);
				} else {
					Set<MethodNode> set = new HashSet<>();
					set.add(caller);
					b.callers.put(callee, set);
				}
			}
		};
		
		for(ClassNode cn : app.iterate())  {
			for(MethodNode m : cn.methods) {
				if(m.name.length() > 2 && !m.name.equals("<init>")) {
					tracer.trace(m);
				}
			}
		}
		
		FrameImpl impl = b.new FrameImpl();
		impl.setPreferredSize(new Dimension(800, 600));
		impl.pack();
		impl.setLocationRelativeTo(null);
		impl.setVisible(true);
	}
	
	@SuppressWarnings("serial")
	class FrameImpl extends JFrame  {
		
		JTree tree;
		
		FrameImpl() {
			super("Callgraph viewer");
			setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			
			DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
			DefaultTreeModel model = new DefaultTreeModel(root);
			
			class TreeMethodNode extends DefaultMutableTreeNode {
				final MethodNode m;
				
				TreeMethodNode(MethodNode m) {
					super(m.toString());
					this.m = m;
				}
			}
			
			System.out.println(callers.size());
			
			getContentPane().setLayout(new BorderLayout());
			
			Map<MethodNode, TreeMethodNode> mnodes = new HashMap<>();
			
			JTextField tf = new JTextField("[search]");
			JTextField tf2 = new JTextField("[results]: ");
			tf2.setEditable(false);
			
			{
				JPanel pan = new JPanel(new GridLayout(1, 2));
				pan.add(tf);
				pan.add(tf2);
				getContentPane().add(pan, BorderLayout.NORTH);
			}
			
			tf.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					String text = tf.getText();
					if(text != null) {
						Set<TreeMethodNode> ms = new HashSet<>();
						for(MethodNode m : callers.keySet()) {
							if(m.toString().contains(text)) {
								TreeMethodNode n = mnodes.get(m);
								ms.add(n);
							}
						}

						tf2.setText("[results]: " + ms.size() + "; " + ms);
						
						if(ms.size() == 1) {
							TreeMethodNode n = ms.iterator().next();
							TreePath path = new TreePath(new Object[]{root, n});
							tree.setSelectionPath(path);
							tree.scrollPathToVisible(path);
							tf.setText("");
						}
					}
				}
			});
			
			tree = new JTree(model);
			tree.setScrollsOnExpand(true);
			tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
//			tree.setRootVisible(false);
			{
				for(MethodNode m : callers.keySet()) {
					if(app.isApplicationClass(m.owner.name)) {
						TreeMethodNode n = new TreeMethodNode(m);
						mnodes.put(m, n);
						root.add(n);
					}
				}
			}

			getContentPane().add(new JScrollPane(tree), BorderLayout.CENTER);
			
			tree.getSelectionModel().addTreeSelectionListener(new TreeSelectionListener() {
				@Override
				public void valueChanged(TreeSelectionEvent e) {
					DefaultMutableTreeNode o = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
					
					if(!(o instanceof TreeMethodNode)) {
						return;
					}
					
					TreeMethodNode n = (TreeMethodNode) o;
					
					if(n.getChildCount() == 0) {
						for(MethodNode m : callers.get(n.m)) {
							n.add(new TreeMethodNode(m));
						}
					}
				}
			});
			
		}
	}
}