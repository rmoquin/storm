package backtype.storm.utils;

import backtype.storm.Config;
import backtype.storm.generated.ComponentCommon;
import backtype.storm.generated.ComponentObject;
import backtype.storm.generated.StormTopology;
import backtype.storm.service.IClassResolverService;
import clojure.lang.IFn;
import clojure.lang.RT;
import clojure.osgi.IClojureLoader;
import com.netflix.curator.framework.CuratorFramework;
import com.netflix.curator.framework.CuratorFrameworkFactory;
import com.netflix.curator.retry.RetryNTimes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.apache.commons.lang.StringUtils;
import org.apache.thrift7.TException;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

public class Utils {
    private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);
    public static final String DEFAULT_STREAM_ID = "default";
    private static Set<IClassResolverService> classResolvers;
    private static IClojureLoader clojureLoader;

    //Thinking about whether or not to use the classResolvers here, since
    //using it should have some safe guards...
    public static Object newInstance(String klass) {
        try {
          if(clojureLoader == null) {
              Class c = Class.forName(klass);
              return c.newInstance();
          } else {
              return clojureLoader.createInstance(Utils.class.getClassLoader(), klass);
          }
        } catch (Exception e) {
            throw new RuntimeException("Couldn't create a new instance for class name: " + klass, e);
        }
    }
    
    public static byte[] serialize(Object obj) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(obj);
            oos.close();
            return bos.toByteArray();
        } catch(IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    public static Object deserialize(final byte[] serialized) {
        //Temporarily store the exception thrown by the class loader
        Exception ex = null;
        try {
            if (clojureLoader == null) {
                ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serialized));
                try {
                    return ois.readObject();
                } finally {
                    ois.close();
                }
            } else {
                return clojureLoader.invoke(Utils.class.getClassLoader(), new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serialized));
                        try {
                            return ois.readObject();
                        } finally {
                            ois.close();
                        }
                    }
                });
            }
        } catch(Exception e) {
          ex = e;
        }
        if(classResolvers != null) {
            for (IClassResolverService resolver : classResolvers) {
                try {
                    return resolver.deserialize(serialized);
                } catch(Exception e) {
                  //Probably no point to even logging anything here...
                }
            }
        }
        throw new RuntimeException("Serialized class couldn't be resolved via any available classloader.", ex);
    }

    public static <T> String join(Iterable<T> coll, String sep) {
        Iterator<T> it = coll.iterator();
        String ret = "";
        while(it.hasNext()) {
            ret = ret + it.next();
            if(it.hasNext()) {
                ret = ret + sep;
            }
        }
        return ret;
    }

    public static void sleep(long millis) {
        try {
            Time.sleep(millis);
        } catch(InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
    
    public static List<URL> findResources(String name) {
        try {
            Enumeration<URL> resources = Utils.class.getClassLoader().getResources(name);
            List<URL> ret = new ArrayList<URL>();
            while(resources.hasMoreElements()) {
                ret.add(resources.nextElement());
            }
            return ret;
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map findAndReadConfigFile(String name, boolean mustExist) {
        try {
            List<URL> resources = findResources(name);
            if(resources.isEmpty()) {
                if(mustExist) throw new RuntimeException("Could not find config file on classpath " + name);
                else return new HashMap();
            }
            URL resource = resources.get(0);
            Yaml yaml = new Yaml();
            Map ret = (Map) yaml.load(new InputStreamReader(resource.openStream()));
            if(ret==null) ret = new HashMap();
            

            return new HashMap(ret);
            
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map findAndReadConfigFile(String name) {
       return findAndReadConfigFile(name, true);
    }

    public static Map readDefaultConfig() {
        return findAndReadConfigFile("defaults.yaml", true);
    }
    
    public static Map readCommandLineOpts() {
        Map ret = new HashMap();
        String commandOptions = System.getProperty("storm.options");
        if(commandOptions != null) {
            commandOptions = commandOptions.replaceAll("%%%%", " ");
            String[] configs = commandOptions.split(",");
            for (String config : configs) {
                String[] options = config.split("=");
                if (options.length == 2) {
                    ret.put(options[0], options[1]);
                }
            }
        }
        return ret;
    }

    public static Map readStormConfig() {
        Map ret = readDefaultConfig();
        String confFile = System.getProperty("storm.conf.file");
        Map storm;
        if (confFile==null || confFile.equals("")) {
            storm = findAndReadConfigFile("storm.yaml", false);
        } else {
            storm = findAndReadConfigFile(confFile, true);
        }
        ret.putAll(storm);
        ret.putAll(readCommandLineOpts());
        return ret;
    }
    
    private static Object normalizeConf(Object conf) {
        if(conf==null) return new HashMap();
        if(conf instanceof Map) {
            Map confMap = new HashMap((Map) conf);
            for(Object key: confMap.keySet()) {
                Object val = confMap.get(key);
                confMap.put(key, normalizeConf(val));
            }
            return confMap;
        } else if(conf instanceof List) {
            List confList =  new ArrayList((List) conf);
            for(int i=0; i<confList.size(); i++) {
                Object val = confList.get(i);
                confList.set(i, normalizeConf(val));
            }
            return confList;
        } else if (conf instanceof Integer) {
            return ((Integer) conf).longValue();
        } else if(conf instanceof Float) {
            return ((Float) conf).doubleValue();
        } else {
            return conf;
        }
    }
    
    public static boolean isValidConf(Map<String, Object> stormConf) {
        return normalizeConf(stormConf).equals(normalizeConf((Map) JSONValue.parse(JSONValue.toJSONString(stormConf))));
    }

    public static Object getSetComponentObject(ComponentObject obj) {
        if(obj.getSetField()==ComponentObject._Fields.SERIALIZED_JAVA) {
            return Utils.deserialize(obj.get_serialized_java());
        } else if(obj.getSetField()==ComponentObject._Fields.JAVA_OBJECT) {
            return obj.get_java_object();
        } else {
            return obj.get_shell();
        }
    }

    public static <S, T> T get(Map<S, T> m, S key, T def) {
        T ret = m.get(key);
        if(ret==null) {
            ret = def;
        }
        return ret;
    }
    
    public static List<Object> tuple(Object... values) {
        List<Object> ret = new ArrayList<Object>();
        for(Object v: values) {
            ret.add(v);
        }
        return ret;
    }

    public static void downloadFromMaster(Map conf, String file, String localFile) throws IOException, TException {
        NimbusClient client = NimbusClient.getConfiguredClient(conf);
        String id = client.getClient().beginFileDownload(file);
        WritableByteChannel out = Channels.newChannel(new FileOutputStream(localFile));
        while(true) {
            ByteBuffer chunk = client.getClient().downloadChunk(id);
            int written = out.write(chunk);
            if(written==0) break;
        }
        out.close();
    }
    
    public static IFn loadClojureFn(String namespace, String name) {
        try {
          clojure.lang.Compiler.eval(RT.readString("(require '" + namespace + ")"));
        } catch (Exception e) {
          //if playing from the repl and defining functions, file won't exist
        }
        return (IFn) RT.var(namespace, name).deref();        
    }
    
    public static boolean isSystemId(String id) {
        return id.startsWith("__");
    }
        
    public static <K, V> Map<V, K> reverseMap(Map<K, V> map) {
        Map<V, K> ret = new HashMap<V, K>();
        for(K key: map.keySet()) {
            ret.put(map.get(key), key);
        }
        return ret;
    }
    
    public static ComponentCommon getComponentCommon(StormTopology topology, String id) {
        if(topology.get_spouts().containsKey(id)) {
            return topology.get_spouts().get(id).get_common();
        }
        if(topology.get_bolts().containsKey(id)) {
            return topology.get_bolts().get(id).get_common();
        }
        if(topology.get_state_spouts().containsKey(id)) {
            return topology.get_state_spouts().get(id).get_common();
        }
        throw new IllegalArgumentException("Could not find component with id " + id);
    }
    
    public static Integer getInt(Object o) {
        if(o instanceof Long) {
            return ((Long) o ).intValue();
        } else if (o instanceof Integer) {
            return (Integer) o;
        } else if (o instanceof Short) {
            return ((Short) o).intValue();
        } else {
            throw new IllegalArgumentException("Don't know how to convert " + o + " + to int");
        }
    }
    
    public static long secureRandomLong() {
        return UUID.randomUUID().getLeastSignificantBits();
    }
    
    
    public static CuratorFramework newCurator(Map conf, List<String> servers, Object port, String root) {
        return newCurator(conf, servers, port, root, null);
    }
    
    public static CuratorFramework newCurator(Map conf, List<String> servers, Object port, String root, ZookeeperAuthInfo auth) {
        List<String> serverPorts = new ArrayList<String>();
        for(String zkServer: (List<String>) servers) {
            serverPorts.add(zkServer + ":" + Utils.getInt(port));
        }
        String zkStr = StringUtils.join(serverPorts, ",") + root; 
        try {
            
            CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                    .connectString(zkStr)
                    .connectionTimeoutMs(Utils.getInt(conf.get(Config.STORM_ZOOKEEPER_CONNECTION_TIMEOUT)))
                    .sessionTimeoutMs(Utils.getInt(conf.get(Config.STORM_ZOOKEEPER_SESSION_TIMEOUT)))
                    .retryPolicy(new RetryNTimes(Utils.getInt(conf.get(Config.STORM_ZOOKEEPER_RETRY_TIMES)), Utils.getInt(conf.get(Config.STORM_ZOOKEEPER_RETRY_INTERVAL))));
            if(auth!=null && auth.scheme!=null) {
                builder = builder.authorization(auth.scheme, auth.payload);
            }            
            return builder.build();
        } catch (IOException e) {
           throw new RuntimeException(e);
        }
    }

    public static CuratorFramework newCurator(Map conf, List<String> servers, Object port) {
        return newCurator(conf, servers, port, "");
    }

    public static CuratorFramework newCuratorStarted(Map conf, List<String> servers, Object port, String root) {
        CuratorFramework ret = newCurator(conf, servers, port, root);
        ret.start();
        return ret;
    }

    public static CuratorFramework newCuratorStarted(Map conf, List<String> servers, Object port) {
        CuratorFramework ret = newCurator(conf, servers, port);
        ret.start();
        return ret;
    }    
    
    /**
     *
(defn integer-divided [sum num-pieces]
  (let [base (int (/ sum num-pieces))
        num-inc (mod sum num-pieces)
        num-bases (- num-pieces num-inc)]
    (if (= num-inc 0)
      {base num-bases}
      {base num-bases (inc base) num-inc}
      )))
     * @param sum
     * @param numPieces
     * @return 
     */
    
    public static TreeMap<Integer, Integer> integerDivided(int sum, int numPieces) {
        int base = sum / numPieces;
        int numInc = sum % numPieces;
        int numBases = numPieces - numInc;
        TreeMap<Integer, Integer> ret = new TreeMap<Integer, Integer>();
        ret.put(base, numBases);
        if(numInc!=0) {
            ret.put(base+1, numInc);
        }
        return ret;
    }

    public static byte[] toByteArray(ByteBuffer buffer) {
        byte[] ret = new byte[buffer.remaining()];
        buffer.get(ret, 0, ret.length);
        return ret;
    }

  /**
   * @return the classResolvers
   */
  public static Set<IClassResolverService> getClassResolvers() {
    return classResolvers;
  }

  /**
   * @param aClassResolvers the classResolvers to set
   */
  public static void setClassResolvers(Set<IClassResolverService> aClassResolvers) {
    classResolvers = aClassResolvers;
  }

  /**
   * @param aClojureLoader the clojureLoader to set
   */
  public static void setClojureLoader(IClojureLoader aClojureLoader) {
    clojureLoader = aClojureLoader;
  }
}
