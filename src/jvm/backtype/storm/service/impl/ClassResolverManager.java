package backtype.storm.service.impl;

import backtype.storm.service.IClassResolverService;
import backtype.storm.utils.Utils;
import clojure.osgi.IClojureLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author rmoquin
 */
public class ClassResolverManager {

  private IClojureLoader clojureLoader;
  private static final Logger LOGGER = LoggerFactory.getLogger(ClassResolverManager.class);
  private Set<IClassResolverService> registeredServices = new CopyOnWriteArraySet<IClassResolverService>();

  public void init() {
    Utils.setClassResolvers(registeredServices);
    Utils.setClojureLoader(clojureLoader);
  }

  public void destroy() {
    this.registeredServices.clear();
  }

  public void onBindService(IClassResolverService service) {
    try {
      if (!this.registeredServices.add(service)) {
        if (LOGGER.isWarnEnabled()) {
          LOGGER.warn("Class resolver service is already added.");
        }
      }
    } catch (Exception ex) {
      LOGGER.warn("An error seems to occur when removing or adding services when a proxy is no longer usable, this is temporarily while I figure it out.", ex);
    }
  }

  public void onUnbindService(IClassResolverService service) {
    if (service == null) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Null service definition was provided for unbind, ignoring.");
      }
      return;
    }
    try {
      if (!this.registeredServices.remove(service)) {
        if (LOGGER.isWarnEnabled()) {
          LOGGER.warn("Class resolver service couldn't be removed because it doesn't appear to exist.");
        }
      }
    } catch (Exception ex) {
      LOGGER.warn("An error seems to occur when removing or adding services when a proxy is no longer usable, this is temporarily while I figure it out.", ex);
    }
  }

  /**
   * @return the clojureLoader
   */
  public IClojureLoader getClojureLoader() {
    return clojureLoader;
  }

  /**
   * @param clojureLoader the clojureLoader to set
   */
  public void setClojureLoader(IClojureLoader clojureLoader) {
    this.clojureLoader = clojureLoader;
  }
}
