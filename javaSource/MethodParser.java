package com.kingdee.solr.client.solrj;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
/*
 * refence http://www.shenyanchao.cn/blog/2013/06/19/use-eclipse-ast-to-parser-java/
 * http://tianya23.blog.51cto.com/1081650/615291
 */
public class MethodParser {

	public static List<String> parse(String source) throws Exception {
		List<String> resultList = new ArrayList<String>();
		// 创建解析器
		ASTParser parsert = ASTParser.newParser(AST.JLS3);
		// 设定解析器的源代码字符
		parsert.setSource(source.toCharArray());
		// 使用解析器进行解析并返回AST上下文结果(CompilationUnit为根节点)
		CompilationUnit result = (CompilationUnit) parsert.createAST(null);
		// 获取类型
		List types = result.types();
		// 取得类型声明
		TypeDeclaration typeDec = (TypeDeclaration) types.get(0);
		// 取得函数(Method)声明列表
		MethodDeclaration methodDec[] = typeDec.getMethods();
		for (MethodDeclaration method : methodDec) {
			int lineNumber = result.getLineNumber(result.getExtendedStartPosition(method));
			System.out.println("method " + method.getName().toString() + "'s line number is " + lineNumber);
			resultList.add(method.getName().toString()); 
	    }
		
		return resultList;
	}

	private static String read(String filename) throws IOException {
		File file = new File(filename);
		byte[] b = new byte[(int) file.length()];
		FileInputStream fis = new FileInputStream(file);
		fis.read(b);
		return new String(b);

	}
	
	public static void main(String[] args) throws IOException, Exception {
		List<String> resultList = parse(read("F:\\Method.java"));
	}
}
