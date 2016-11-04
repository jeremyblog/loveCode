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

	final HashMap<MethodDeclaration, ArrayList<MethodInvocation>> invocationsForMethods = new HashMap<MethodDeclaration, ArrayList<MethodInvocation>>();
	private final String path;
	private final CompilationUnit result;

	public MethodHrefBeta(String path) throws Exception {
		this.path = path;
		this.result = getCompilationUnit();
	}

	public String getPackageName() {
		return result.getPackage().getName().toString();
	}

	public Map<String, ArrayList<String>> getMethods(){
		Map<String, ArrayList<String>> resultMap = new HashMap<String, ArrayList<String>>();
		TypeDeclaration type = (TypeDeclaration) result.types().get(0);
		MethodDeclaration[] methodList = type.getMethods();// 获取方法的注释以及方法体
		for(MethodDeclaration m : methodList) {
			List<String> calls = new ArrayList<String> ();
			String methodName = m.getName().toString();
			for(MethodInvocation i : invocationsForMethods.get(m)){
				calls.add(i.getName().toString());//<methodA, new A().methodA()>
			}
			
			resultMap.put(methodName, (ArrayList<String>) calls);
		}
		
		return resultMap;
	}
	
	public String getClassName(){
		String[] result = path.split("\\\\");
		return result[result.length -1];
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
				if (invocationsForMethods.get(activeMethod) == null) {
					invocationsForMethods.put(activeMethod, new ArrayList<MethodInvocation>());
				}
				invocationsForMethods.get(activeMethod).add(node);
				return super.visit(node);
			}

		});
		return result;

	}
	
	public static void main(String[] args) {
		try {
			MethodHrefBeta m = new MethodHrefBeta("F:\\source\\test\\A\\B.java");
			System.out.println(m.getPackageName());
			System.out.println(m.getClassName());
			Map<String,ArrayList<String>> methods = m.getMethods();
			for(String method : methods.keySet()) {
				for(String call : methods.get(method)) {
				System.out.println(method + "call method " + call );
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
