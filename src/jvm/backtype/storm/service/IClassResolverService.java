package backtype.storm.service;

import java.io.IOException;

/**
 *
 * @author rmoquin
 */
public interface IClassResolverService {

  Object deserialize(byte[] so) throws IOException, ClassNotFoundException;

  Object newInstance(String className) throws ClassNotFoundException, InstantiationException, IllegalAccessException;
  
}
