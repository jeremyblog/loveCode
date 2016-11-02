#!/usr/bin/env groovy

import groovy.text.SimpleTemplateEngine
import groovy.text.Template
import groovy.text.TemplateEngine
import groovy.io.FileType

def sources
if (this.args.size() !=1 )  {
	println "please set the dirs of source"
}else{
	sources = args[0]
}

createDir("target")
createDir("target/html")

def pwd = ""
def output = ""
TemplateEngine engine = new SimpleTemplateEngine()
Template template = engine.createTemplate(new File("templates/JAVA.html"))

def special_symbol = [:]
special_symbol = specialSybmbol()

def package_list = []
def package_class_map = [:]
def class_method_map = [:]
//def java = []
def packageAndJava = [:]
def import_beizhu = new StringBuffer()
def ids = []
//create html
new File(sources).traverse(type:FileType.FILES,
        //nameFilter:~/.*\.(java|property|xml)/) {
	nameFilter:~/.*\.(java)/) {
	def strbuf = new StringBuffer()
	name = it.getName();
	
	
	if(it.isFile()) {
		def line = 0;
		it.eachLine("utf8"){
			//add 2016.10.29 remove import
			if(it.startsWith("import")) {
				import_beizhu.append('<p height=\"1em\" width=\"0\">' + it + '</p>\n')
			} else {
				strbuf.append("<p height=\"1em\" width=\"0\">" + doWithSpecial(special_symbol,it) + "</p>\n")
			}
		}
	}

	//add 2016.10.29 remove import
	strbuf.append('<p height=\"1em\" width=\"0\"></p>\n')
	strbuf.append('<p height=\"1em\" width=\"0\"><span id=\"zhu_import\">[import]</span></p>\n' + import_beizhu.toString())
	def content = '<sup><a href=\"#zhu_import\">[import]</a></sup>\n' + strbuf.toString()

	Writable result  = template.make(content:content,name:name)
	id = name.split(/\.(java|property|xml)/)[0]
	id = doWithMutiId(ids,id)
	ids << id
	packageName = getPackage(it.getPath(), sources, name)
	savePackage(package_list,packageName)
	savePackage_class(package_class_map,p, id) {
	//---old---
	save(packageAndJava , packageName , id)
	//java << id
	new File("target/html/" +  id + '.html').withWriter('utf-8'){
		writer -> result.writeTo(writer)
	}
}

def itembuf = new StringBuffer()
def navItemBuf = new StringBuffer()
//def contentItembuf = new StringBuffer()
def ncxBuf = new StringBuffer()

//java.each{
n = 1
packageAndJava.keySet().each {
	l = packageAndJava.get(it)

	//contentItembuf.append('<h4 height=\"1em\">' + it + '</h4>\n')
	//contentItembuf.append('<ul>\n')
	ncxBuf.append('<navPoint playOrder=\"' + n +'\" class=\"section\" id=\"item-' + n + '\">\n')
	ncxBuf.append('<navLabel>\n')
	ncxBuf.append('<text>' + it + '</text>')
	ncxBuf.append('</navLabel>')
	n++
	ncxBuf.append('<content src=\"html/' + l[0] +'.html\" />')

	l.each {
		itembuf.append('<item href=\"html/' + it + '.html\" media-type=\"application/xhtml+xml\" id=\"' + it + '\"/>\n')
		navItemBuf.append('<itemref idref=\"' + it + '\"/>\n')
		//fix for it
		//contentItembuf.append('<li><a href=\"' + it + '.html\">' + it + '</a></li>\n')
		//navs
		ncxBuf.append('<navPoint playOrder=\"'+ n + '\" class=\"article\" id=\"' + id + n + '\">\n')
		ncxBuf.append('<navLabel>')
		ncxBuf.append('<text>' + it + '</text>\n')
		ncxBuf.append('			</navLabel>')
		ncxBuf.append('			<content src=\"html/'+ it + '.html\" />\n')
		ncxBuf.append('			<mbp:meta name=\"description\">forget it</mbp:meta>\n')
		ncxBuf.append('		</navPoint>\n')
		n++
	}
	//contentItembuf.append('</ul>\n')
	ncxBuf.append('</navPoint>\n')
}

//create opf
template = engine.createTemplate(new File("templates/java.opf"))
result  = template.make(item:itembuf,navitem:navItemBuf)
new File("target/java.opf").withWriter('utf-8'){
		writer -> result.writeTo(writer)
}

//creat contents
//template = engine.createTemplate(new File(pwd + "templates/toc.html"))
//result  = template.make(contentItem:contentItembuf)
//new File("target/html/contents.html").withWriter('utf-8'){
//	writer -> result.writeTo(writer)
//}
createToc(output, package_list, package_class_map,class_method_map)

//creat ncx
template = engine.createTemplate(new File("templates/nav-contents.ncx"))
result  = template.make(navPoints:ncxBuf)
new File("target/nav-contents.ncx").withWriter('utf-8'){
	writer -> result.writeTo(writer)
}

def createToc(output, package_list, package_class_map,class_method_map){
	def tocvalues = new StringBuffer()
	package_list.each {
		packageName = it
		def tocclasses = new StringBuffer()
		package_class_map.get(it).each{
			className = it
			def tocmethods = new StringBuffer()
			class_method_map.get(it).each {
				template = engine.createTemplate(new File(pwd + "templates/toc-class-method.tl"))
				tocmethods.append(template.make(tocmethodhref:'empty',tocmethodname:it))
			}

			template = engine.createTemplate(new File(pwd + "templates/toc-class.tl"))
			tocclasses.append(template.make(tocclasshref:makeHtmlHref(packageName, className,null) ,tocclassname: className))
		}
		template = engine.createTemplate(new File(pwd + "templates/toc-package.tl"))
		tocvalues.append(template.make(tocpackageshortname:packageName.substring(packageName.lastIndexOf('\\'))
			,tocclasshref:makeHtmlHref(packageName, null,null) ,tocpackagelongname: packageName))

	}

	template = engine.createTemplate(new File(pwd + "templates/toc.html"))
	result  = template.make(tocvalues:tocvalues)

	new File(output + "target/html/toc.html").withWriter('utf-8'){
		writer -> result.writeTo(writer)
	}
}

def makeHtmlHref(p,c,m){
	if(m) {
		return p + '-' + c + '.html#' + m
	} else if(c) {
		return p + '-' + c + '.html#'
	} else {
		return p + '.html#'
	}
}

def savePackage(package_list, p_name){
	package_list << n
}

def savePackage_class(package_class_map,p_name, c_name) {
	if(package_class_map.containsKey(p_name)) {
		map.get(p_name).add(c_name)
	} else {
		def l = []
		l << c_name
		package_class_map[p_name] = l
	}
}

def saveClass_method(class_method_map, c_name, m_name) {
	if(class_method_map.containsKey(c_name)) {
		map.get(c_name).add(m_name)
	} else {
		def l = []
		l << m_name
		class_method_map[c_name] = l
	}
}

//---old----

def createDir(dir){
	def target = new File(dir);
	if(target.exists()){
		target.deleteDir()
	}
	target.mkdirs()
}

def getPackage(path, s, n){
	return path.substring(path.indexOf(s) + s.size(),path.size() - n.size())
}

def save(map,key,value){
	if(map.containsKey(key)) {
		map.get(key).add(value)
	} else {
		def l = []
		l << value
		map[key] = l
	}
}

//add 2016.10.29 do with muti ids
def doWithMutiId(ids,id){
	if (id in ids) {
		return id + '-' + System.currentTimeMillis() + '' + ids.size()
	}

	return id;
}

def specialSybmbol(){
	result = [:]
	//result.put("\"","&quot;")
	//result.put("&","&amp;")
	result.put("<","&lt;")
	//result.put(">","&gt;")
	result.put(" ","&nbsp;")
	//result.put("?","&iexcl;")
	//result.put("|","&brvbar;")
	//result.put("·","&middot;")
	//result.put("÷","&divide;")

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