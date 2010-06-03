package org.collectionspace.chain.csp.persistence.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.commons.lang.StringUtils;
import org.collectionspace.chain.csp.persistence.services.vocab.URNProcessor;
import org.collectionspace.chain.csp.schema.Field;
import org.collectionspace.chain.csp.schema.FieldSet;
import org.collectionspace.chain.csp.schema.Record;
import org.collectionspace.chain.csp.schema.Repeat;
import org.collectionspace.csp.api.persistence.ExistException;
import org.collectionspace.csp.api.persistence.UnderlyingStorageException;
import org.dom4j.Document;
import org.dom4j.DocumentFactory;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.Node;
import org.dom4j.QName;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XmlJsonConversion {
	private static final Logger log=LoggerFactory.getLogger(XmlJsonConversion.class);
	private static void addFieldToXml(Element root,Field field,JSONObject in) throws JSONException {
		String value=in.optString(field.getID());
		Element element=root.addElement(field.getServicesTag());
		element.addText(value);
	}
	
	private static void addRepeatToXml(Element root,Repeat repeat,JSONObject in,String section) throws JSONException, UnderlyingStorageException {
		Element element=root;
		if(!repeat.getXxxServicesNoRepeat()) {	// Sometimes the UI is ahead of the services layer
			element=root.addElement(repeat.getServicesTag());
		}
		Object value=null;
		if(repeat.getXxxUiNoRepeat()) { // and sometimes the app ahead of the services
			FieldSet[] children=repeat.getChildren();
			if(children.length==0)
				return;
			addFieldSetToXml(element,children[0],in,section);
			return;
		} else {
			value=in.opt(repeat.getID());			
		}		
		if(value==null || ((value instanceof String) && StringUtils.isBlank((String)value)))
			return;
		if(value instanceof String) { // And sometimes the services ahead of the UI
			JSONArray next=new JSONArray();
			next.put(value);
			value=next;
		}
		if(!(value instanceof JSONArray))
			throw new UnderlyingStorageException("Bad JSON in repeated field: must be string or array for repeatable field");
		JSONArray array=(JSONArray)value;
		for(int i=0;i<array.length();i++) {
			Object one_value=array.get(i);
			if(one_value==null || ((one_value instanceof String) && StringUtils.isBlank((String)one_value)))
				continue;
			if(one_value instanceof String) {
				// Assume it's just the first entry (useful if there's only one)
				FieldSet[] fs=repeat.getChildren();
				if(fs.length<1)
					continue;
				JSONObject d1=new JSONObject();
				d1.put(fs[0].getID(),one_value);
				addFieldSetToXml(element,fs[0],d1,section);
			} else if(one_value instanceof JSONObject) {
				for(FieldSet fs : repeat.getChildren())
					addFieldSetToXml(element,fs,(JSONObject)one_value,section);
			}
		}
	}

	private static void addFieldSetToXml(Element root,FieldSet fs,JSONObject in,String section) throws JSONException, UnderlyingStorageException {
		if(!section.equals(fs.getSection()))
			return;
		if(fs instanceof Field)
			addFieldToXml(root,(Field)fs,in);
		else if(fs instanceof Repeat)
			addRepeatToXml(root,(Repeat)fs,in,section);
	}
	
	public static Document convertToXml(Record r,JSONObject in,String section) throws JSONException, UnderlyingStorageException {
		Document doc=DocumentFactory.getInstance().createDocument();
		String[] parts=r.getServicesRecordPath(section).split(":",2);
		String[] rootel=parts[1].split(",");
		Element root=doc.addElement(new QName(rootel[1],new Namespace("ns2",rootel[0])));
		for(FieldSet f : r.getAllFields()) {
			addFieldSetToXml(root,f,in,section);
		}
		String test = doc.asXML();
		log.debug(doc.asXML());
		return doc;
	}
	
	@SuppressWarnings("unchecked")
	private static void addFieldToJson(JSONObject out,Element root,Field f) throws JSONException {
		List nodes=root.selectNodes(f.getServicesTag());
		if(nodes.size()==0)
			return;
		// XXX just add first
		Element el=(Element)nodes.get(0);
		//Fields that have an autocomplete tag, should also have a sibling with the de-urned version of the urn to display nicely
		if(f.hasAutocompleteInstance()){
			String deurned = getDeURNedValue(f, el.getText());
			if(deurned !=""){
				out.put("de-urned-"+f.getID(), deurned);
			}
		}
		out.put(f.getID(),el.getText());
	}

	private static String getDeURNedValue(Field f, String urn) throws JSONException {
		//add a field with the de-urned version of the urn
		if(urn.isEmpty() || urn == null){
			return "";
		}
		if(f.getAutocompleteInstance()!=null){
			String urnsyntax = f.getAutocompleteInstance().getRecord().getURNSyntax();
			URNProcessor urnp = new URNProcessor(urnsyntax);
			try {
				return urnp.deconstructURN(urn,false)[5];
			} catch (ExistException e) {
				return "";
			} catch (UnderlyingStorageException e) {
				return "";
			}
		}
		return "";
	}

	private static void buildFieldList(List<String> list,FieldSet f) {
		if(f instanceof Repeat){
			list.add(f.getID());
			for(FieldSet a : ((Repeat)f).getChildren()){
				//if(a instanceof Repeat)
				//	list.add(a.getID());
				if(a instanceof Field)
					list.add(a.getID());
				//buildFieldList(list,a);
			}
		}
		if(f instanceof Field)
			list.add(f.getID());
	}
	
	/* Repeat syntax is challenging for dom4j */
	private static List<Map<String, List <Element> >> extractRepeats(Element container,FieldSet f) {
		List<Map<String,List<Element>>> out=new ArrayList<Map<String,List<Element>>>();
		// Build index so that we can see when we return to the start
		List<String> fields=new ArrayList<String>();
		List<Element> repeatdatatypestuff = new ArrayList<Element>();
		buildFieldList(fields,f);
		Map<String,Integer> field_index=new HashMap<String,Integer>();
		for(int i=0;i<fields.size();i++){
			field_index.put(fields.get(i),i);
		}
		// Iterate through
		Map<String, List <Element> > member=null;
		int prev=Integer.MAX_VALUE;
		for(Object node : container.selectNodes("*")) {
			if(!(node instanceof Element))
				continue;
			Integer next=field_index.get(((Element)node).getName());
			if(next==null)
				continue;
			if(next<prev) {
				// Must be a new instance
				if(member!=null)
					out.add(member);
				member=new HashMap<String, List <Element> >();
				repeatdatatypestuff = new ArrayList<Element>();
			}
			prev=next;
			repeatdatatypestuff = new ArrayList<Element>();
			repeatdatatypestuff.add((Element)node);
			member.put(((Element)node).getName(),repeatdatatypestuff);
		}
		if(member!=null)
			out.add(member);
		
		return out;
	}
	
	@SuppressWarnings("unchecked")
	private static void addRepeatedNodeToJson(JSONObject out,Element container,Repeat f) throws JSONException {
		List<Map<String,List <Element>>> elementlist=extractRepeats(container,f);
		JSONArray array=new JSONArray();
		JSONArray repeatarray = new JSONArray();
		for(Map<String,List <Element>> grouplist : elementlist) {
			// For each repeat
			JSONObject member=new JSONObject();
			for(FieldSet fs : f.getChildren()) {
				List <Element> childlist = grouplist.get(fs.getServicesTag());
				for(Element child: childlist){
					if(child==null)
						continue;
					if(fs instanceof Field) {
						if(((Field)fs).hasAutocompleteInstance()){
							String deurned = getDeURNedValue((Field)fs,child.getText());
							if(deurned !=""){
								member.put("de-urned-"+fs.getID(), deurned);
							}
						}
						
						if(f.getXxxUiNoRepeat()){
							member.put(f.getID(),child.getText());}
						else{
							//changed this as fields within repeats were not doing as expected
							// used fs.getID() rather than f.getID()
							member.put(fs.getID(),child.getText());
						}
					} else if(fs instanceof Repeat) {
						JSONObject rp=new JSONObject();
						addRepeatedNodeToJson(rp,child,(Repeat)fs);
						repeatarray.put(rp);
					}
					if(f.getXxxUiNoRepeat()) {
						out.put(fs.getID(),member.get(f.getID()));
						return;
					}
					
					//check if there are attributes and add them to the node
					List<Node> attributes = container.selectNodes("@*");
					for(Node n : attributes){
						member.put(n.getName(),n.getText());
					}
				}
				if(repeatarray.length() > 0)
					array.put(repeatarray);
			}
			if(member.length() > 0)
				array.put(member);
		}
		if(out.has(f.getID())){
			//smoosh together multiple array items
			JSONArray data = out.getJSONArray(f.getID());
			for(int i=0;i<data.length();i++) {
				int index = data.length() + i;
				array.put(array.length(),data.get(i));
			}
		}
		out.put(f.getID(),array);
	}
	
	@SuppressWarnings("unchecked")
	private static void addRepeatToJson(JSONObject out,Element root,Repeat f) throws JSONException {
		if(f.getXxxServicesNoRepeat()) {
			FieldSet[] fields=f.getChildren();
			if(fields.length==0)
				return;
			JSONArray members=new JSONArray();
			JSONObject data=new JSONObject();
			addFieldSetToJson(data,root,fields[0]);
			members.put(data);
			out.put(f.getID(),members);
			return;
		}
		List nodes=root.selectNodes(f.getServicesTag());
		if(nodes.size()==0)
			return;
		// Only first element is important in container
		//except when we have repeating items
		for(Object repeatcontainer : nodes){
			Element container=(Element)repeatcontainer;
			addRepeatedNodeToJson(out,container,f);
		}
		
	}
	
	private static void addFieldSetToJson(JSONObject out,Element root,FieldSet fs) throws JSONException {
		if(fs instanceof Field)
			addFieldToJson(out,root,(Field)fs);
		if(fs instanceof Repeat)
			addRepeatToJson(out,root,(Repeat)fs);
	}
	
	public static void convertToJson(JSONObject out,Record r,Document doc) throws JSONException {
		Element root=doc.getRootElement();
		for(FieldSet f : r.getAllFields()) {
			addFieldSetToJson(out,root,f);
			
		}
	}

	public static JSONObject convertToJson(Record r,Document doc) throws JSONException {
		JSONObject out=new JSONObject();
		convertToJson(out,r,doc);
		return out;
	}
}
