package nz.co.fortytwo.signalk.artemis.service;

import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.CONFIG;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.aircraft;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.aton;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.dot;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.meta;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.resources;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.sar;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.self_str;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.sentence;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.sourceRef;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.sources;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.timestamp;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.value;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.values;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.version;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.vessels;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.influxdb.BatchOptions;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Point.Builder;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.influxdb.dto.QueryResult.Series;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;

import mjson.Json;
import nz.co.fortytwo.signalk.artemis.util.Config;
import nz.co.fortytwo.signalk.artemis.util.Util;

public class InfluxDbService implements TDBService {

	private static final String STR_VALUE = "strValue";
	private static final String LONG_VALUE = "longValue";
	private static final String DOUBLE_VALUE = "doubleValue";
	private static final String NULL_VALUE = "nullValue";
	private static Logger logger = LogManager.getLogger(InfluxDbService.class);
	private InfluxDB influxDB;
	private static String dbName = "signalk";
	
	public static final String PRIMARY_VALUE = "primary";
	public static final ConcurrentSkipListMap<String, String> primaryMap = new ConcurrentSkipListMap<>();
	
	public InfluxDbService() {
		setUpTDb();
	}

	public InfluxDbService(String dbName) {
		this.dbName=dbName;
		setUpTDb();
	}

	/* (non-Javadoc)
	 * @see nz.co.fortytwo.signalk.artemis.service.TDBService#setUpInfluxDb()
	 */
	@Override
	public void setUpTDb() {
		influxDB = InfluxDBFactory.connect("http://localhost:8086", "admin", "admin");
		if (!influxDB.databaseExists(dbName))
			influxDB.createDatabase(dbName);
		influxDB.setDatabase(dbName);
		
		influxDB.enableBatch(BatchOptions.DEFAULTS.exceptionHandler((failedPoints, throwable) -> {
			logger.error("FAILED:"+failedPoints);
			logger.error(throwable);
		}));
		loadPrimary();
	}

	/* (non-Javadoc)
	 * @see nz.co.fortytwo.signalk.artemis.service.TDBService#closeInfluxDb()
	 */
	@Override
	public void closeTDb() {
		influxDB.close();
	}
	
	@Override
	public NavigableMap<String, Json> loadResources(NavigableMap<String, Json> map, Map<String, String> query) {
		//"select * from "+table+" where uuid='"+uuid+"' AND skey=~/"+pattern+"/ group by skey,primary, uuid,sourceRef,owner,grp order by time desc limit 1"
		String queryStr="select * from resources "+getWhereString(query)+" group by skey, primary, uuid,sourceRef,owner,grp order by time desc limit 1";
		return loadResources(map, queryStr);
	}

	private String getWhereString(Map<String, String> query) {
		StringBuilder builder = new StringBuilder();
		if(query==null || query.size()==0)return "";
		String joiner=" where ";
		for(Entry<String, String> e: query.entrySet()){
			builder.append(joiner)
				.append(e.getKey())
				.append("=~/")
				.append(e.getValue())
				.append("/");
			joiner=" and ";
		}
		return builder.toString();
	}

	@Override
	public NavigableMap<String, Json> loadConfig(NavigableMap<String, Json> map, Map<String, String> query) {
		String queryStr="select * from config "+getWhereString(query)+" group by skey,owner,grp order by time desc limit 1";
		return loadConfig(map, queryStr);
	}

	@Override
	public NavigableMap<String, Json> loadData(NavigableMap<String, Json> map, String table, Map<String, String> query) {
		String queryStr="select * from "+table+getWhereString(query)+" group by skey,primary, uuid,sourceRef,owner,grp order by time desc limit 1";
		return loadData(map, queryStr);
	}

	@Override
	public NavigableMap<String, Json> loadSources(NavigableMap<String, Json> map, Map<String, String> query) {
		String queryStr="select * from sources "+getWhereString(query)+" group by skey,owner,grp order by time desc limit 1";
		return loadSources(map, queryStr);
	}
	
	public NavigableMap<String, Json> loadResources(NavigableMap<String, Json> map, String queryStr) {
		if (logger.isDebugEnabled())logger.debug("Query: {}", queryStr);
		Query query = new Query(queryStr, dbName);
		QueryResult result = influxDB.query(query);
		
		if (result == null || result.getResults() == null)
			return map;
		result.getResults().forEach((r) -> {
			if (r == null ||r.getSeries()==null)
				return;
			r.getSeries().forEach((s) -> {
				if (logger.isDebugEnabled())logger.debug(s);
				if(s==null)return;
				
				Map<String, String> tagMap = s.getTags();
				String key = s.getName() + dot + s.getTags().get("skey");
				Json attr = getAttrJson(tagMap);
				Json val = getJsonValue(s,0);
				if(key.contains(".values.")){
					//handle values
					if (logger.isDebugEnabled())logger.debug("values: {}",val);
					String parentKey = StringUtils.substringBeforeLast(key,".values.");
					String valKey = StringUtils.substringAfterLast(key,".values.");
					String subkey = StringUtils.substringAfterLast(valKey,".value.");
					
					//make parent Json
					Json parent = getParent(map,parentKey,attr);
						
					//add attributes
					if (logger.isDebugEnabled())logger.debug("Primary value: {}",tagMap.get("primary"));
					boolean primary = Boolean.valueOf((String)tagMap.get("primary"));
					if(primary){
						extractPrimaryValue(parent,s,subkey,val, false);
					}else{
						
						valKey=StringUtils.substringBeforeLast(valKey,".");
						Json valuesJson = parent.at(values);
						if(valuesJson==null){
							valuesJson = Json.object();
							parent.set(values,valuesJson);
						}
						Json subJson = valuesJson.at(valKey);
						if(subJson==null){
							subJson = Json.object();
							valuesJson.set(valKey,subJson);
						}
						extractValue(subJson,s,subkey, val, false);
					}
					
					return;
				}
				if( (key.endsWith(".value")||key.contains(".value."))){
					if (logger.isDebugEnabled())logger.debug("value: {}",val);
					String subkey = StringUtils.substringAfterLast(key,".value.");
				
					key = StringUtils.substringBeforeLast(key,".value");
					
					//make parent Json
					Json parent = getParent(map,key,attr);
					if (logger.isDebugEnabled())logger.debug("Primary value: {}",tagMap.get("primary"));
					boolean primary = Boolean.valueOf((String)tagMap.get("primary"));
					if(primary){
						extractPrimaryValue(parent,s,subkey,val, false);
					}else{
						extractValue(parent,s,subkey, val, false);
					}
					//extractValue(parent,s, subkey, val);
					
					map.put(key,parent);
					return;
				}
				
				map.put(key,val);
				map.put(key+"._attr",attr);
				
			});
		});
		return map;
	}

	public NavigableMap<String, Json> loadConfig(NavigableMap<String, Json> map, String queryStr) {
		Query query = new Query(queryStr, dbName);
		QueryResult result = influxDB.query(query);
		
		if (result == null || result.getResults() == null)
			return map;
		result.getResults().forEach((r) -> {
			if (r.getSeries() == null ||r.getSeries()==null)
				return;
			r.getSeries().forEach((s) -> {
				if (logger.isDebugEnabled())logger.debug(s);
				if(s==null)return;
				
				Map<String, String> tagMap = s.getTags();
				String key = s.getName() + dot + tagMap.get("skey");
				Json attr = getAttrJson(tagMap);
				Json val = getJsonValue(s,0);
				
				map.put(key,val);
				map.put(key+"._attr",attr);
				
			});
		});
		return map;
	}

	
	public NavigableMap<String, Json> loadData(NavigableMap<String, Json> map, String queryStr){
		if (logger.isDebugEnabled())logger.debug("queryStr: {}",queryStr);
		Query query = new Query(queryStr, dbName);
		QueryResult result = influxDB.query(query);
		//NavigableMap<String, Json> map = new ConcurrentSkipListMap<>();
		if (logger.isDebugEnabled())logger.debug(result);
		if(result==null || result.getResults()==null)return map;
		result.getResults().forEach((r)-> {
			if (logger.isDebugEnabled())logger.debug(r);
			if(r==null||r.getSeries()==null)return;
			r.getSeries().forEach(
				(s)->{
					if (logger.isDebugEnabled())logger.debug(s);
					if(s==null)return;
					
					Map<String, String> tagMap = s.getTags();
					String key = s.getName()+dot+tagMap.get("uuid")+dot+tagMap.get("skey");
					Json attr = getAttrJson(tagMap);
					
					Json val = getJsonValue(s,0);
					
					
					boolean processed = false;
					//add timestamp and sourceRef
					if(key.endsWith(".sentence")){
						if (logger.isDebugEnabled())logger.debug("sentence: {}",val);
						//make parent Json
						String parentKey = StringUtils.substringBeforeLast(key,".");
						
						Json parent = getParent(map,parentKey,attr);
				
						parent.set(sentence,val);
						processed=true;
						return;
						
					}
					if(key.contains(".meta.")){
						//add meta to parent of value
						if (logger.isDebugEnabled())logger.debug("meta: {}",val);
						String parentKey = StringUtils.substringBeforeLast(key,".meta.");
						String metaKey = StringUtils.substringAfterLast(key,".meta.");
						
						//make parent Json
						Json parent = getParent(map,parentKey,attr);
						
						//add attributes
						addAtPath(parent,"meta."+metaKey, val);
						processed=true;
						return;
					}
					if(key.contains(".values.")){
						//handle values
						if (logger.isDebugEnabled())logger.debug("values: {}",val);
						String parentKey = StringUtils.substringBeforeLast(key,".values.");
						String valKey = StringUtils.substringAfterLast(key,".values.");
						String subkey = StringUtils.substringAfterLast(valKey,".value.");
						
						//make parent Json
						Json parent = getParent(map,parentKey,attr);
							
						//add attributes
						if (logger.isDebugEnabled())logger.debug("Primary value: {}",tagMap.get("primary"));
						boolean primary = Boolean.valueOf((String)tagMap.get("primary"));
						if(primary){
							extractPrimaryValue(parent,s,subkey,val,true);
						}else{
							
							valKey=StringUtils.substringBeforeLast(valKey,".");
							Json valuesJson = parent.at(values);
							if(valuesJson==null){
								valuesJson = Json.object();
								parent.set(values,valuesJson);
							}
							Json subJson = valuesJson.at(valKey);
							if(subJson==null){
								subJson = Json.object();
								valuesJson.set(valKey,subJson);
							}
							extractValue(subJson,s,subkey, val, true);
						}
						
						processed=true;
					}
					if(!processed && (key.endsWith(".value")||key.contains(".value."))){
						if (logger.isDebugEnabled())logger.debug("value: {}",val);
						String subkey = StringUtils.substringAfterLast(key,".value.");
					
						key = StringUtils.substringBeforeLast(key,".value");
						
						//make parent Json
						Json parent = getParent(map,key,attr);
						if (logger.isDebugEnabled())logger.debug("Primary value: {}",tagMap.get("primary"));
						boolean primary = Boolean.valueOf((String)tagMap.get("primary"));
						if(primary){
							extractPrimaryValue(parent,s,subkey,val,true);
						}else{
							extractValue(parent,s,subkey, val, true);
						}
						//extractValue(parent,s, subkey, val);
						
						map.put(key,parent);
						processed=true;
					}
					if(!processed){
						map.put(key,val);
						map.put(key+"._attr",attr);
					}
					
				});
			});
		return map;
	}

	

	private Json getParent(NavigableMap<String, Json> map, String parentKey, Json attr) {
		
		//make parent Json
		Json parent = map.get(parentKey);
		if(parent==null){
			parent = Json.object();
			map.put(parentKey,parent);
			map.put(parentKey+"._attr",attr);
		}
		return parent;
	}

	public NavigableMap<String, Json> loadSources(NavigableMap<String, Json> map, String queryStr) {
		Query query = new Query(queryStr, dbName);
		QueryResult result = influxDB.query(query);
		// NavigableMap<String, Json> map = new ConcurrentSkipListMap<>();
		
		if (logger.isDebugEnabled())logger.debug(result);
		if (result == null || result.getResults() == null)
			return map;
		result.getResults().forEach((r) -> {
			if (logger.isDebugEnabled())logger.debug(r);
			if (r.getSeries() == null)return;
			r.getSeries().forEach((s) -> {
				
				if (logger.isDebugEnabled())logger.debug("Load source: {}",s);
				if (s == null)return;
				
				String key = s.getName() + dot + s.getTags().get("skey");
				Json attr = getAttrJson(s.getTags());
				if (logger.isDebugEnabled())logger.debug("Load source map: {} = {}",()->key,()->getJsonValue(s,0));
				map.put(key, getJsonValue(s,0));
				map.put(key+"._attr",attr);

			});
		});
		return map;
	}

	private Object getValue(String field, Series s, int row) {
		int i = s.getColumns().indexOf(field);
		if (i < 0)
			return null;
		return (s.getValues().get(row)).get(i);
	}

	private Json getJsonValue(Series s, int row) {
		Object obj = getValue(LONG_VALUE, s, 0);
		if (obj != null)
			return Json.make(Math.round((Double) obj));
		obj = getValue(NULL_VALUE, s, 0);
		if (obj != null && Boolean.valueOf((String)obj)) return Json.nil();
		
		obj = getValue(DOUBLE_VALUE, s, 0);
		if (obj != null)
			return Json.make(obj);

		obj = getValue(STR_VALUE, s, 0);
		if (obj != null) {
			if (obj.equals("true")) {
				return Json.make(true);
			}
			if (obj.equals("false")) {
				return Json.make(false);
			}
			if (obj.toString().trim().startsWith("[") && obj.toString().endsWith("]")) {
				return Json.read(obj.toString());
			}
			
			return Json.make(obj);

		}
		return Json.nil();

	}
	
	private Json getAttrJson(Map<String,String> tagMap) {
		Json attr = Json.object()
				.set(SecurityService.OWNER, tagMap.get(SecurityService.OWNER))
				.set(SecurityService.GROUP, tagMap.get(SecurityService.GROUP));
		return attr;
	}

	/* (non-Javadoc)
	 * @see nz.co.fortytwo.signalk.artemis.service.TDBService#save(java.util.NavigableMap)
	 */
	@Override
	public void save(NavigableMap<String, Json> map) {
		if (logger.isDebugEnabled())logger.debug("Save map:  {}" ,map);
		for(Entry<String, Json> e: map.entrySet()){
			save(e.getKey(), e.getValue(), map.get(e.getKey()+"._attr"));
		}
		influxDB.flush();
	}

	/* (non-Javadoc)
	 * @see nz.co.fortytwo.signalk.artemis.service.TDBService#save(java.lang.String, mjson.Json, mjson.Json)
	 */
	@Override
	public void save(String k, Json v, Json attr) {
		if (logger.isDebugEnabled())logger.debug("Save json:  {}={}" , k , v);
		//avoid _attr
		if(k.contains("._attr")){
			return;
		}
		
		String srcRef = (v.isObject() && v.has(sourceRef) ? v.at(sourceRef).asString() : "self");
		long tStamp = (v.isObject() && v.has(timestamp) ? Util.getMillisFromIsoTime(v.at(timestamp).asString())
				: System.currentTimeMillis());
		save(k,v,srcRef, tStamp, attr);
	}
	
	/* (non-Javadoc)
	 * @see nz.co.fortytwo.signalk.artemis.service.TDBService#save(java.lang.String, mjson.Json, java.lang.String, long, mjson.Json)
	 */
	@Override
	public void save(String k, Json v, String srcRef ,long tStamp, Json attr) {
		if (v.isPrimitive()|| v.isBoolean()) {
			
			if (logger.isDebugEnabled())logger.debug("Save primitive:  {}={}", k, v);
			saveData(k, srcRef, tStamp, v.getValue(),attr);	
			return;
		}
		if (v.isNull()) {
			
			if (logger.isDebugEnabled())logger.debug("Save null: {}={}",k , v);
			saveData(k, srcRef, tStamp, null,attr);
			return;
		}
		if (v.isArray()) {
			
			if (logger.isDebugEnabled())logger.debug("Save array: {}={}", k , v);
			saveData(k, srcRef, tStamp, v,attr);
			return;
		}
		if (v.has(sentence)) {
			saveData(k + dot + sentence, srcRef, tStamp, v.at(sentence),attr);
		}
		if (v.has(meta)) {
			for (Entry<String, Json> i : v.at(meta).asJsonMap().entrySet()) {
				
				if (logger.isDebugEnabled())logger.debug("Save meta: {}={}",()->i.getKey(), ()->i.getValue());
				saveData(k + dot + meta + dot + i.getKey(), srcRef, tStamp, i.getValue(),attr);
			}
		}
		
		if (v.has(values)) {
		
			for (Entry<String, Json> i : v.at(values).asJsonMap().entrySet()) {
				
				if (logger.isDebugEnabled())logger.debug("Save values: {}={}",()->i.getKey() ,()-> i.getValue());
				String sRef = StringUtils.substringBefore(i.getKey(),dot+value);
				Json vs = i.getValue();
				long ts = (vs.isObject() && vs.has(timestamp) ? Util.getMillisFromIsoTime(vs.at(timestamp).asString())
						: tStamp);
				save(k,i.getValue(),sRef, ts, attr);
				
			}
		}
		
		if (v.has(value)&& v.at(value).isObject()) {
			for (Entry<String, Json> i : v.at(value).asJsonMap().entrySet()) {
				
				if (logger.isDebugEnabled())logger.debug("Save value object: {}={}" , ()->i.getKey(),()->i.getValue());
				saveData(k + dot + value + dot + i.getKey(), srcRef, tStamp, i.getValue(),attr);
			}
			return;
		}

		
		if (logger.isDebugEnabled())logger.debug("Save value: {} : {}", k, v);
		saveData(k + dot + value, srcRef, tStamp, v.at(value),attr);

		return;
	}

	private void addAtPath(Json parent, String path, Json val) {
		String[] pathArray = StringUtils.split(path, ".");
		Json node = parent;
		for (int x = 0; x < pathArray.length; x++) {
			// add last
			if (x == (pathArray.length - 1)) {
				
				if (logger.isDebugEnabled())logger.debug("finish: {}",pathArray[x]);
				node.set(pathArray[x], val);
				break;
			}
			// get next node
			Json next = node.at(pathArray[x]);
			if (next == null) {
				next = Json.object();
				node.set(pathArray[x], next);
				
				if (logger.isDebugEnabled())logger.debug("add: {}", pathArray[x]);
			}
			node = next;
		}

	}

	
	private void extractPrimaryValue(Json parent, Series s, String subKey, Json val, boolean useValue) {
		String srcref = s.getTags().get("sourceRef");
		if (logger.isDebugEnabled())logger.debug("extractPrimaryValue: {}:{}",s, srcref);
		
		Json node = parent;
		Object ts = getValue("time", s, 0);
		if (ts != null) {
			// make predictable 3 digit nano ISO format
			ts = Util.getIsoTimeString(DateTime.parse((String)ts, ISODateTimeFormat.dateTimeParser()).getMillis());
			node.set(timestamp, Json.make(ts));
		}
		
		if (srcref != null) {
			node.set(sourceRef, Json.make(srcref));
		}

		if(useValue){
			// check if its an object value
			if (StringUtils.isNotBlank(subKey)) {
				Json valJson = Util.getJson(parent,value );
				valJson.set(subKey, val);
			} else {
				node.set(value, val);
			}
			//if we have a 'values' key, and its primary, copy back into value{} 
			if(parent.has(values)){
				Json pValues = Json.object(value,parent.at(value).dup(),timestamp,parent.at(timestamp).dup());
				parent.at(values).set(parent.at(sourceRef).asString(),pValues);
			}
		}else{
			// check if its an object value
			if (StringUtils.isNotBlank(subKey)) {
				parent.set(subKey, val);
			} else {
				node.set(value, val);
			}
			//if we have a 'values' key, and its primary, copy back into value{} 
			if(parent.has(values)){
				Json pValues = parent.dup();
				parent.delAt(values);
				parent.delAt(sourceRef);
				parent.at(values).set(parent.at(sourceRef).asString(),pValues);
			}
		}
		if (logger.isDebugEnabled())logger.debug("extractPrimaryValueObj: {}",parent);
	}
	
	private void extractValue(Json parent, Series s, String attr, Json val, boolean useValue) {
		String srcref = s.getTags().get("sourceRef");
		if (logger.isDebugEnabled())logger.debug("extractValue: {}:{}",s, srcref);
		Json node = parent;
		
		if (StringUtils.isNotBlank(srcref)) {
			node = Util.getJson(parent,values );
			node.set(srcref,Json.object());
			node=node.at(srcref);
		}
		Object ts = getValue("time", s, 0);
		if (ts != null) {
			// make predictable 3 digit nano ISO format
			ts = Util.getIsoTimeString(DateTime.parse((String)ts, ISODateTimeFormat.dateTimeParser()).getMillis());
			node.set(timestamp, Json.make(ts));
		}
			
		// check if its an object value
		if(useValue){
			if (StringUtils.isNotBlank(attr)) {
				Json valJson = parent.at(value);
				if (valJson == null) {
					valJson = Json.object();
					node.set(value, valJson);
				}
				valJson.set(attr, val);
			} else {
				node.set(value, val);
			}
			if (StringUtils.isNotBlank(srcref)) {
				//if we have a 'value' copy it into values.srcRef.{} too
				if(parent.has(value)){
					Json pValues = Json.object(value,parent.at(value),timestamp,parent.at(timestamp));
					parent.at(values).set(parent.at(sourceRef).asString(),pValues);
				}
			}
		}else{
			//TODO: what happens here?
			if (StringUtils.isNotBlank(attr)) {
				Json valJson = parent.at(value);
				if (valJson == null) {
					valJson = Json.object();
					node.set(value, valJson);
				}
				valJson.set(attr, val);
			} else {
				node.set(value, val);
			}
			if (StringUtils.isNotBlank(srcref)) {
				//if we have a 'value' copy it into values.srcRef.{} too
				if(parent.has(value)){
					Json pValues = Json.object(value,parent.at(value),timestamp,parent.at(timestamp));
					parent.at(values).set(parent.at(sourceRef).asString(),pValues);
				}
			}
		}
		
		if (logger.isDebugEnabled())logger.debug("extractValue: {}",parent);
	}


	protected void saveData(String key, String sourceRef, long millis, Object val, Json attr) {
		
			if(val!=null)
				if (logger.isDebugEnabled())logger.debug("save {} : {}",()->val.getClass().getSimpleName(),()->key);
			else{
				if (logger.isDebugEnabled())logger.debug("save {} : {}",()->null,()->key);
			}
			
			if(self_str.equals(key))return;
			if(version.equals(key))return;
			String[] path = StringUtils.split(key, '.');
			String field = getFieldType(val);
			Builder point = null;
			switch (path[0]) {	
			case resources:
				point = Point.measurement(path[0]).time(millis, TimeUnit.MILLISECONDS)
						.tag("sourceRef", sourceRef)
						.tag("uuid", path[1])
						.tag(SecurityService.OWNER, attr.at(SecurityService.OWNER).asString())
						.tag(SecurityService.GROUP, attr.at(SecurityService.GROUP).asString())
						.tag(InfluxDbService.PRIMARY_VALUE, isPrimary(key,sourceRef).toString())
						.tag("skey", String.join(".", ArrayUtils.subarray(path, 1, path.length)));
				influxDB.write(addPoint(point, field, val));
				break;
			case sources:
				point = Point.measurement(path[0]).time(millis, TimeUnit.MILLISECONDS)
						.tag("sourceRef", path[1])
						.tag(SecurityService.OWNER, attr.at(SecurityService.OWNER).asString())
						.tag(SecurityService.GROUP, attr.at(SecurityService.GROUP).asString())
						.tag("skey", String.join(".", ArrayUtils.subarray(path, 1, path.length)));
				influxDB.write(addPoint(point, field, val));
				break;
			case CONFIG:
				point = Point.measurement(path[0]).time(millis, TimeUnit.MILLISECONDS)
						.tag(SecurityService.OWNER, attr.at(SecurityService.OWNER).asString())
						.tag(SecurityService.GROUP, attr.at(SecurityService.GROUP).asString())
						.tag("uuid", path[1])
						.tag("skey", String.join(".", ArrayUtils.subarray(path, 1, path.length)));
				influxDB.write(addPoint(point, field, val));
				//also update the config map
				Config.setProperty(String.join(".", path), Json.make(val));
				break;
			case vessels:
				writeToInflux(path, millis, key, sourceRef, field, attr,val);
				break;
			case aircraft:
				writeToInflux(path, millis, key, sourceRef, field, attr,val);
				break;
			case sar:
				writeToInflux(path, millis, key, sourceRef, field, attr,val);
				break;
			case aton:
				writeToInflux(path, millis, key, sourceRef, field, attr,val);
				break;
			default:
				break;
			}
		
		
	}

	private void writeToInflux(String[] path, long millis, String key, String sourceRef, String field, Json attr, Object val) {
		Boolean primary = isPrimary(key,sourceRef);
		Builder point = Point.measurement(path[0]).time(millis, TimeUnit.MILLISECONDS)
				.tag("sourceRef", sourceRef)
				.tag("uuid", path[1])
				.tag(SecurityService.OWNER, attr.at(SecurityService.OWNER).asString())
				.tag(SecurityService.GROUP, attr.at(SecurityService.GROUP).asString())
				.tag(InfluxDbService.PRIMARY_VALUE, primary.toString())
				.tag("skey", String.join(".", ArrayUtils.subarray(path, 2, path.length)));
		influxDB.write(addPoint(point, field, val));
		
	}

	/* (non-Javadoc)
	 * @see nz.co.fortytwo.signalk.artemis.service.TDBService#loadPrimary()
	 */
	@Override
	public void loadPrimary(){
		primaryMap.clear();
		logger.info("Adding primaryMap");
		QueryResult result = influxDB.query(new Query("select * from vessels where primary='true' group by skey,primary,uuid,owner,grp,sourceRef order by time desc limit 1",dbName));
		if(result==null || result.getResults()==null)return ;
		result.getResults().forEach((r)-> {
			if(logger.isTraceEnabled())logger.trace(r);
			if(r==null||r.getSeries()==null)return;
			r.getSeries().forEach(
				(s)->{
					if(logger.isTraceEnabled())logger.trace(s);
					if(s==null)return;
					
					Map<String, String> tagMap = s.getTags();
					String key = s.getName()+dot+tagMap.get("uuid")+dot+StringUtils.substringBeforeLast(tagMap.get("skey"),dot+value);
					primaryMap.put(StringUtils.substringBefore(key,dot+values+dot), tagMap.get("sourceRef"));
					if(logger.isTraceEnabled())logger.trace("Primary map: {}={}",key,tagMap.get("sourceRef"));
				});
		});
	}
	/* (non-Javadoc)
	 * @see nz.co.fortytwo.signalk.artemis.service.TDBService#isPrimary(java.lang.String, java.lang.String)
	 */
	@Override
	public Boolean isPrimary(String key, String sourceRef) {
		//truncate the .values. part of the key
		String mapRef = primaryMap.get(StringUtils.substringBefore(key,dot+values+dot));
		
		if(mapRef==null){
			//no result = primary
			setPrimary(key, sourceRef);
			if (logger.isDebugEnabled())logger.debug("isPrimary: {}={} : {}",key,sourceRef, true);
			return true;
		}
		if(StringUtils.equals(sourceRef, mapRef)){
			if (logger.isDebugEnabled())logger.debug("isPrimary: {}={} : {}, {}",key,sourceRef, mapRef, true);
			return true;
		}
		if (logger.isDebugEnabled())logger.debug("isPrimary: {}={} : {}, {}",key,sourceRef, mapRef, false);
		return false;
	}
	
	/* (non-Javadoc)
	 * @see nz.co.fortytwo.signalk.artemis.service.TDBService#setPrimary(java.lang.String, java.lang.String)
	 */
	@Override
	public Boolean setPrimary(String key, String sourceRef) {
		if (logger.isDebugEnabled())logger.debug("setPrimary: {}={}",key,sourceRef);
		return !StringUtils.equals(sourceRef, primaryMap.put(StringUtils.substringBefore(key,dot+values+dot),sourceRef));
	}

	private Point addPoint(Builder point, String field, Object jsonValue) {
		Object value = null;
		if (logger.isDebugEnabled())logger.debug("addPoint: {} : {}",field,jsonValue);
		if(jsonValue==null)return point.addField(field,true).build();
		if(jsonValue instanceof Json){
			if(((Json)jsonValue).isString()){
				value=((Json)jsonValue).asString();
			}else if(((Json)jsonValue).isNull()){
				value=true;
			}else if(((Json)jsonValue).isArray()){
				value=((Json)jsonValue).toString();
			}else{
				value=((Json)jsonValue).getValue();
			}
		}else{
			value=jsonValue;
		}
		if(value instanceof Boolean)return point.addField(field,((Boolean)value).toString()).build();
		if(value instanceof Double)return point.addField(field,(Double)value).build();
		if(value instanceof Float)return point.addField(field,(Double)value).build();
		if(value instanceof BigDecimal)return point.addField(field,((BigDecimal)value).doubleValue()).build();
		if(value instanceof Long)return point.addField(field,(Long)value).build();
		if(value instanceof Integer)return point.addField(field,((Integer)value).longValue()).build();
		if(value instanceof BigInteger)return point.addField(field,((BigInteger)value).longValue()).build();
		if(value instanceof String)return point.addField(field,(String)value).build();
		if (logger.isDebugEnabled())logger.debug("addPoint: unknown type: {} : {}",field,value);
		return null;
	}

	private String getFieldType(Object value) {
		
		if(value==null){
			if (logger.isDebugEnabled())logger.debug("getFieldType: {} : {}",value,value);
			return NULL_VALUE;
		}
		if (logger.isDebugEnabled())logger.debug("getFieldType: {} : {}",value.getClass().getName(),value);
		if(value instanceof Json){
			if(((Json)value).isNull())return NULL_VALUE;
			if(((Json)value).isArray())return STR_VALUE;
			value=((Json)value).getValue();
		}
		if(value instanceof Double)return DOUBLE_VALUE;
		if(value instanceof BigDecimal)return DOUBLE_VALUE;
		if(value instanceof Long)return LONG_VALUE;
		if(value instanceof BigInteger)return LONG_VALUE;
		if(value instanceof Integer)return LONG_VALUE;
		if(value instanceof String)return STR_VALUE;
		if(value instanceof Boolean)return STR_VALUE;
		
		if (logger.isDebugEnabled())logger.debug("getFieldType:unknown type: {} : {}",value.getClass().getName(),value);
		return null;
	}

	/* (non-Javadoc)
	 * @see nz.co.fortytwo.signalk.artemis.service.TDBService#close()
	 */
	@Override
	public void close() {
		influxDB.close();
	}

	public InfluxDB getInfluxDB() {
		return influxDB;
	}

	public static String getDbName() {
		return dbName;
	}

	public static void setDbName(String dbName) {
		InfluxDbService.dbName = dbName;
	}


}
