package io.github.jeremy.kindlejava;

import java.io.File;
import java.util.Map;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyCodeSource;
import groovy.lang.GroovyObject;

public class Main {
	// public static String GROOVY_PATH =
	// Main.class.getClassLoader().getResource("").getPath() + "main/groovy";
	public static String GROOVY_PATH = "F:\\workspace\\loveCode\\kindlejava\\src\\main\\groovy\\";

	public static void main(String[] args) throws Exception {
		String path = "F:\\source\\test\\";
		File file = new File(path);
		createHtml(file);
	}

	public static void createHtml(File file) throws Exception {
		for (File f : file.listFiles()) {
			if (f.isDirectory()) {
				createHtml(f);
			} else if (f.isFile()) {
				MethodHrefBeta beta = new MethodHrefBeta(f.getAbsolutePath());
				Map<Integer, JavaHref> hrefs = beta.getJavaHrefs();
				GroovyClassLoader classLoader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader());
				File sourceFile = new File(GROOVY_PATH + "/HtmlUtil3.groovy");
				Class testGroovyClass = classLoader.parseClass(new GroovyCodeSource(sourceFile));
				GroovyObject instance = (GroovyObject) testGroovyClass.newInstance();// proxy
				JavaHref test = new JavaHref();
				String result = (String) instance.invokeMethod("createHtml",
						new Object[] { GROOVY_PATH, "F:\\test\\target\\", beta.getP().name, beta.getC().name,
								f.getAbsolutePath().toString(), hrefs });
			}
		}
	}
}
