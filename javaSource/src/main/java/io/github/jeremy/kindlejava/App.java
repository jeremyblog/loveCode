package io.github.jeremy.kindlejava;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

/**
 * Hello world!
 *
 */
public class App {
	static final HashMap<MethodDeclaration, ArrayList<MethodInvocation>> invocationsForMethods = new HashMap<MethodDeclaration, ArrayList<MethodInvocation>>();
	private static ASTParser astParser = ASTParser.newParser(AST.JLS3); // 非常慢

	public static void main(String[] args) throws Exception {
		CompilationUnit result = getCompilationUnit("F:\\source\\test\\A\\B.java");
//		result = getCompilationUnit("F:\\source\\test\\A\\B.java");
		// List commentList = result.getCommentList();// 获取注释信息,包含 doc注释和单行注释
		// PackageDeclaration package1 = result.getPackage();// 获取所在包信息
		// 如:"package
		// readjavafile;"
		// List importList = result.imports();// 获取导入的包
		// TypeDeclaration type = (TypeDeclaration) result.types().get(0);//
		// 获取文件中的第一个类声明(包含注释)
		// FieldDeclaration[] fieldList = type.getFields();// 获取类的成员变量

		// MethodDeclaration[] methodList = type.getMethods();// 获取方法的注释以及方法体

		// Type method_type = methodList[0].getReturnType2();// 获取返回值类型 如 void
		// SimpleName method_name = methodList[0].getName();// 获取方法名 main
		// Javadoc o1 = methodList[0].getJavadoc();// 获取方法的注释
		// List o4 = methodList[0].thrownExceptions();// 异常
		// List o5 = methodList[0].modifiers();// 访问类型如:[public, static]
		// List o6 = methodList[0].parameters();// 获取参数:[String[] args]
		// Block method_block = methodList[0].getBody();//
		// 获取方法的内容如:"{System.out.println("Hello");}"
		// List statements = method_block.statements();// 获取方法内容的所有行
		// ExpressionStatement sta = (ExpressionStatement) statements.get(0);//
		// 获取第一行的内

		for (MethodDeclaration activeMethod : invocationsForMethods.keySet()) {
			for (MethodInvocation method : invocationsForMethods.get(activeMethod)) {
				System.out.println(activeMethod.getName() + " calls method " + method.getName());
				System.out.println(method.getName() + " calls method " + method.getParent().toString());
			}
		}
	}

	/**
	 * 获得java源文件的结构CompilationUnit
	 */
	public static CompilationUnit getCompilationUnit(String javaFilePath) throws Exception {
		BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(javaFilePath));
		byte[] input = new byte[bufferedInputStream.available()];
		bufferedInputStream.read(input);
		bufferedInputStream.close();
		astParser.setSource(new String(input).toCharArray());

		CompilationUnit result = (CompilationUnit) (astParser.createAST(null)); // 很慢
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

}
