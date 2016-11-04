#!/usr/bin/env groovy

import groovy.text.SimpleTemplateEngine
import groovy.text.Template
import groovy.text.TemplateEngine
import groovy.io.FileType

PACKAGESYMBOL = "."

createDir("target")
createDir("target/html")

special_symbol = [:]
special_symbol = specialSybmbol()

def Main(pwd, output){
	return pwd + ' , ' + output
}

def createHtml(pwd, output, p_name, c_name, package_class_map, file, hrefs) {
	def strbuf = new StringBuffer()
	def import_beizhu = new StringBuffer()
	
	TemplateEngine engine = new SimpleTemplateEngine()
	template = engine.createTemplate(new File(pwd + "templates/html-content.tl"))

	i = 1;
	new File(file).eachLine("utf8") {
		//add 2016.10.29 remove import
		if(it.startsWith("import")) {
			import_beizhu.append(template.make(content:it).toString())
			import_beizhu.append("\n")
		} else {
			strbuf.append(template.make(content:doWithSpecial(special_symbol,it)).toString())
			strbuf.append("\n")
		}
		if(hrefs.get(i)) {
			print(i)
			print(hrefs.get(i).name)
		}
		i++
	}
	//add 2016.10.29 remove import
	strbuf.append("\n")
	strbuf.append('<p height=\"1em\" width=\"0\"><span id=\"zhu_import\">[import]</span></p>\n' + import_beizhu.toString())
	def content = '<sup><a href=\"#zhu_import\">[import]</a></sup>\n' + strbuf.toString()

	template = engine.createTemplate(new File("templates/JAVA.html"))
	Writable result  = template.make(content:content,name:c_name)

	html =  output + 'target/' +  makeHtmlHref(packageName, c_name, null);
	new File(html.toString()).withWriter('utf-8'){
		writer -> result.writeTo(writer)
	}
}

//sort
//package_list.unique()
//package_list.sort()

//create
//createPackageHtml(pwd,output,package_list)
//createOPF(pwd,output, package_list, package_class_map,project)
//createToc(pwd,output, package_list, package_class_map,class_method_map)
//createNCX(pwd, output, package_list, package_class_map,project)

//copy(pwd,output)

}

def getFileName(n){
	return n.split(/\.(java|property|xml)/)[0]
}

def copy(pwd,output){
	source = new File(pwd + "templates/JAVA.css")
	target = new File(output + "target/JAVA.css")
	target << source.text

	source = new File(pwd + "templates/Specifications.html")
	target = new File(output + "target/Specifications.html")
	target << source.text

	source = new File(pwd + "templates/WImage-cover.gif")
	target = new File(output + "target/WImage-cover.gif")
	target << source.text
}

def createPackageHtml(pwd,output,package_list){
	TemplateEngine engine = new SimpleTemplateEngine()
	template = engine.createTemplate(new File("templates/html-packge.html"))
	package_list.each {
		html =  output + 'target/' +  makeHtmlHref(it, null, null);
		Writable result  = template.make(content:'',name:it)
		new File(html.toString()).withWriter('utf-8'){
			writer -> result.writeTo(writer)
		}
	}
}

def createOPF(pwd,output, package_list, package_class_map,project){
	TemplateEngine engine = new SimpleTemplateEngine()
	def items = new StringBuffer()
	def spines = new StringBuffer()
	def i = 1
	package_list.each {
		p = it
		template = engine.createTemplate(new File(pwd + "templates/opf-item.tl"))
		items.append(template.make(itemhref:makeHtmlHref(p, null,null),i:i).toString())
		template = engine.createTemplate(new File(pwd + "templates/opf-spine.tl"))
		spines.append(template.make(i:i).toString())
		i++
		package_class_map.get(it).each {
			c = it
			template = engine.createTemplate(new File(pwd + "templates/opf-item.tl"))
			items.append(template.make(itemhref:makeHtmlHref(p, c,null),i:i).toString())

			template = engine.createTemplate(new File(pwd + "templates/opf-spine.tl"))
			spines.append(template.make(i:i).toString())
			i ++
		}
	}
	template = engine.createTemplate(new File(pwd + "templates/JAVA.opf"))
	result  = template.make(items:items,spines:spines,bookName:project)
	new File(output + "target/" + project + ".opf").withWriter('utf-8'){
		writer -> result.writeTo(writer)
	}
}

def createNCX(pwd, output, package_list, package_class_map,bookName) {
	TemplateEngine engine = new SimpleTemplateEngine()
	def ncxchaters = new StringBuffer()
	def n = 2
	def i = 1
	package_list.each {
		print(it+"\n")
		packageName = it
		cNum = n
		def ncxsetions = new StringBuilder()
		package_class_map.get(it).each {
			className = it
			n++
			template = engine.createTemplate(new File(pwd + "templates/ncx-section.tl"))
			ncxsetions.append(template.make(ncxsetion: it,ncxsetionhref:makeHtmlHref(packageName, className,null),ncxN: n).toString())
		}
		template = engine.createTemplate(new File(pwd + "templates/ncx-chater.tl"))
		ncxchaters.append(template.make(ncxchater:it ,ncxchaterhref: makeHtmlHref(packageName, null,null), ncxN:cNum,ncxsetions:ncxsetions,i:i).toString())
		n++
		i++
	}
	template = engine.createTemplate(new File(pwd + "templates/JAVA.ncx"))
	result  = template.make(ncxchaters:ncxchaters,bookName:bookName)

	new File(output + "target/JAVA.ncx").withWriter('utf-8'){
		writer -> result.writeTo(writer)
	}
}

def createToc(pwd,output, package_list, package_class_map,class_method_map){
	TemplateEngine engine = new SimpleTemplateEngine()
	def tocvalues = new StringBuffer()
	package_list.each {
		packageName = it
		def tocclasses = new StringBuffer()
		package_class_map.get(it).each{
			className = it
			def tocmethods = new StringBuffer()
			class_method_map.get(it).each {
				template = engine.createTemplate(new File(pwd + "templates/toc-class-method.tl"))
				tocmethods.append(template.make(tocmethodhref:'empty',tocmethodname:it).toString())
			}

			template = engine.createTemplate(new File(pwd + "templates/toc-class.tl"))
			tocclasses.append(template.make(tocclasshref:makeHtmlHref(packageName, className,null) ,tocclassname: className,tocmethods:tocmethods).toString())
		}
		template = engine.createTemplate(new File(pwd + "templates/toc-package.tl"))
		tocvalues.append(template.make(tocpackageshortname:packageName.substring(packageName.lastIndexOf(PACKAGESYMBOL))
			,tocpackagehref:makeHtmlHref(packageName, null,null) ,tocpackagelongname: packageName,tocclasses:tocclasses).toString())

	}

	template = engine.createTemplate(new File(pwd + "templates/toc.html"))
	result  = template.make(tocvalues:tocvalues)

	new File(output + "target/toc.html").withWriter('utf-8'){
		writer -> result.writeTo(writer)
	}
}

def makeHtmlHref(p,c,m){
	if(m) {
		return p + '-' + c + '.html#' + m
	} else if(c) {
		return p + '-' + c + '.html'
	} else {
		return p + 'package.html'
	}
}

def savePackage(package_list, p_name){
	package_list << p_name
}

def savePackage_class(package_class_map,p_name, c_name) {
	if(package_class_map.containsKey(p_name)) {
		package_class_map.get(p_name).add(c_name)
	} else {
		def l = []
		l << c_name
		package_class_map[p_name] = l
	}
}

def saveClass_method(class_method_map, c_name, m_name) {
	if(class_method_map.containsKey(c_name)) {
		class_method_map.get(c_name).add(m_name)
	} else {
		def l = []
		l << m_name
		class_method_map[c_name] = l
	}
}

def createDir(dir){
	def target = new File(dir);
	if(target.exists()){
		target.deleteDir()
	}
	target.mkdirs()
}

//TODO must fix it
def getPackage(path, s, n){
	return path.substring(path.indexOf(s) + s.size(),path.size() - n.size()).replaceAll('\\\\',PACKAGESYMBOL)
}

def specialSybmbol(){
	result = [:]
	result.put("<","&lt;")
	result.put(" ","&nbsp;")
	result.put("\t","&nbsp;&nbsp;&nbsp;&nbsp;")

	return result
}

def doWithSpecial(map, input) {

	map.each{ k,v->
		if (input.indexOf(k) > -1 ){		
			input = input.replaceAll(k, v)
		}
	}

	return input
}