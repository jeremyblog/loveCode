package io.github.jeremy.kindlejava;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;

/**
 * beta just for method in one file
 * 
 * @author kingdee
 *
 */
public class MethodHrefBeta {
	private static ASTParser astParser = ASTParser.newParser(AST.JLS3); // 非常慢

	final HashMap<String, ArrayList<MethodInvocation>> invocationsForMethods = new HashMap<String, ArrayList<MethodInvocation>>();
	private final String path;
	private final CompilationUnit result;
	public final IPackage p;
	public final IClass c;
	public final List<IMethod> methods;

	public MethodHrefBeta(String path) throws Exception {
		this.path = path;
		this.result = getCompilationUnit();
		p = getPackageName();
		c = getClassName();
		methods = getMethods();
	}

	public IPackage getP() {
		return p;
	}

	public IClass getC() {
		return c;
	}

	private IPackage getPackageName() {
		IPackage p = new IPackage();
		p.setName(result.getPackage().getName().toString());
		return p;
	}

	private List<IMethod> getMethods(){
		List<IMethod> resultList = new ArrayList<IMethod>();
		TypeDeclaration type = (TypeDeclaration) result.types().get(0);
		MethodDeclaration[] methodList = type.getMethods();// 获取方法的注释以及方法体
		IClass c = getClassName();
		for(MethodDeclaration m : methodList) {
			IMethod i = new IMethod();
			i.name = m.getName().toString();
			i.className = c.name;
			i.startLine = result.getLineNumber(m.getStartPosition());
			resultList.add(i);
		}
		
		for(IMethod m : resultList) {
			if(invocationsForMethods.get(m.name) == null) {
				continue;
			}
			for(MethodInvocation i : invocationsForMethods.get(m.name)){
				List<ICalled> calleds = new ArrayList<ICalled>();
				if(selfMethod(resultList, i.getName().toString())) {
					ICalled e = new ICalled();
					e.setName(i.getName().toString());
					e.startLine = result.getLineNumber(i.getStartPosition());
					calleds.add(e);
				}
				m.calleds = calleds;
			}
		}
		
		return resultList;
	}
	
	private boolean selfMethod(List<IMethod> ims , String name){
		for(IMethod i : ims) {
			if(i.getName().equals(name)) {
				return true;
			}
		}
		return false;
	}
	
	private IClass getClassName(){
		String[] result = path.split("\\\\");
		IClass c = new IClass();
		c.setName(result[result.length -1]);
		return c;
	}
	
	public Map<Integer,JavaHref> getJavaHrefs(){
		Map<Integer,JavaHref> resultList = new HashMap<Integer,JavaHref>();
		for(IMethod m : methods) {
			JavaHref h1 = new JavaHref();
			h1.type = 0;
			h1.name = m.name;
			resultList.put(m.startLine, h1);
			if(m.calleds == null) {
				continue;
			}
			for(ICalled ic : m.calleds) {
				JavaHref h2 = new JavaHref();
				h2.type = 1;
				h2.name = ic.name;
				resultList.put(ic.startLine, h2);
//				System.out.println("it call " + ic.name + " in " + ic.startLine);
			}
		}
		
		return resultList;
	}

	/**
	 * 获得java源文件的结构CompilationUnit
	 */
	public CompilationUnit getCompilationUnit() throws Exception {
		BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(path));
		byte[] input = new byte[bufferedInputStream.available()];
		bufferedInputStream.read(input);
		bufferedInputStream.close();
		astParser.setSource(new String(input).toCharArray());

		CompilationUnit result = (CompilationUnit) (astParser.createAST(null));
		result.accept(new ASTVisitor() {

			private MethodDeclaration activeMethod;

			@Override
			public boolean visit(MethodDeclaration node) {
				activeMethod = node;
				return super.visit(node);
			}

			@Override
			public boolean visit(MethodInvocation node) {
				if (invocationsForMethods.get(activeMethod.getName().toString()) == null) {
					invocationsForMethods.put(activeMethod.getName().toString(), new ArrayList<MethodInvocation>());
				}
				invocationsForMethods.get(activeMethod.getName().toString()).add(node);
				return super.visit(node);
			}

		});
		return result;

	}
	
	public static void main(String[] args) {
		try {
			MethodHrefBeta m = new MethodHrefBeta("F:\\source\\test\\A\\B.java");
			/*for(IMethod i : m.getMethods()) {
				System.out.println("method name " + i.name + " in " + i.startLine);
				for(ICalled ic : i.calleds) {
					System.out.println("it call " + ic.name + " in " + ic.startLine);
				}
			}*/
			Map<Integer,JavaHref> result = m.getJavaHrefs();
			for( int l : result.keySet()){
				JavaHref h = result.get(l);
				System.out.println(h.name + " is " + h.type + " in line " +l );
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
