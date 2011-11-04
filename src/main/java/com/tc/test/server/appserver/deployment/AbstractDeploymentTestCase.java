/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.tc.test.AppServerInfo;
import com.tc.test.TCTestCase;
import com.tc.test.TestConfigObject;
import com.tc.test.server.appserver.load.LowMemWorkaround;
import com.tc.text.Banner;
import com.tc.util.TcConfigBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestSuite;

public abstract class AbstractDeploymentTestCase extends TCTestCase {
  protected Log         logger              = LogFactory.getLog(getClass());

  private ServerManager serverManager;

  private final Map     disabledVariants    = new HashMap();
  private final List    disabledJavaVersion = new ArrayList();
  private final boolean isExpressMode;

  public static Test suite() {
    return new ErrorTestSetup(new TestSuite(AbstractDeploymentTestCase.class));
  }

  public AbstractDeploymentTestCase() {
    isExpressMode = TestConfigObject.getInstance().isExpressModeForAppserver();
  }

  @Override
  protected boolean isContainerTest() {
    return true;
  }

  public boolean shouldDisable() {
    if (LowMemWorkaround.lessThan2Gb()) { return true; }
    
    if (!TestConfigObject.getInstance().transparentTestsMode().equals(TestConfigObject.TRANSPARENT_TESTS_MODE_NORMAL)) { 
      Banner.warnBanner("NOT RUNNNING TEST BECAUSE TEST MODE IS NOT 'normal'");
      return true; 
    }
    
    return shouldBeSkipped() || isAllDisabled() || shouldDisableForJavaVersion() || shouldDisableForVariants()
           || (isExpressMode() && !canRunExpressMode());
  }

  @Override
  protected void beforeTimeout() throws Throwable {
    getServerManager().timeout();
  }

  protected boolean shouldKillAppServersEachRun() {
    return true;
  }

  protected AppServerInfo appServerInfo() {
    return TestConfigObject.getInstance().appServerInfo();
  }

  /**
   * override this method to pass extra arguments to the L2
   */
  protected Collection getExtraJvmArgsForL2() {
    return Collections.EMPTY_LIST;
  }

  @Override
  public void runBare() throws Throwable {
    if (this.shouldBeSkipped()) {
      Banner.warnBanner("Test " + this.getClass().getName()
                        + " is skipped because it's not configured to run with an appserver");
    }
    if (shouldDisable()) { return; }
    super.runBare();
  }

  protected ServerManager getServerManager() {
    if (serverManager == null) {
      try {
        serverManager = ServerManagerUtil.startAndBind(getClass(), isWithPersistentStore(), getSessionLocking(),
                                                       getSynchronousWrite(), getExtraJvmArgsForL2());
      } catch (Exception e) {
        throw new RuntimeException("Unable to create server manager; " + e.toString(), e);
      }
    }
    return serverManager;
  }

  @Override
  protected void tearDown() throws Exception {
    if (shouldKillAppServersEachRun()) {
      ServerManagerUtil.stopAllWebServers(serverManager);
    }
    super.tearDown();
  }

  protected WebApplicationServer makeWebApplicationServer(TcConfigBuilder configBuilder) throws Exception {
    return getServerManager().makeWebApplicationServer(configBuilder);
  }

  protected WebApplicationServer makeWebApplicationServer(TcConfigBuilder configBuilder, boolean addExpress)
      throws Exception {
    return getServerManager().makeWebApplicationServer(configBuilder, addExpress);
  }

  protected void restartDSO() throws Exception {
    getServerManager().restartDSO(isWithPersistentStore());
  }

  protected DeploymentBuilder makeDeploymentBuilder(String warFileName) {
    return getServerManager().makeDeploymentBuilder(warFileName);
  }

  protected DeploymentBuilder makeDeploymentBuilder(String warFileName, boolean enableExpress) {
    return getServerManager().makeDeploymentBuilder(warFileName, enableExpress);
  }

  protected void waitForSuccess(int timeoutInSeconds, TestCallback callback) throws Throwable {
    long startingTime = System.currentTimeMillis();
    long timeout = timeoutInSeconds * 1000;
    while (true) {
      try {
        logger.debug("checking");
        callback.check();
        logger.debug("check passed");
        return;
      } catch (Throwable e) {
        logger.debug("check failed");
        if ((System.currentTimeMillis() - startingTime) >= timeout) {
          logger.debug("check timed out", e);
          throw e;
        }
      }
      logger.debug("check sleeping");
      Thread.sleep(100L);
    }
  }

  protected void stopAllWebServers() {
    ServerManagerUtil.stopAllWebServers(getServerManager());
  }

  protected boolean isWithPersistentStore() {
    return false;
  }

  @Override
  protected final boolean cleanTempDir() {
    return false;
  }

  protected void disableVariant(String variantName, String variantValue) {
    List variantList = (List) disabledVariants.get(variantName);
    if (variantList == null) {
      variantList = new ArrayList();
      disabledVariants.put(variantName, variantList);
    }
    variantList.add(variantValue);
  }

  protected void disableForJavaVersion(String version) {
    this.disabledJavaVersion.add(version);
  }

  void disableAllTests() {
    this.disableTest();
  }

  private boolean shouldDisableForVariants() {
    for (Iterator iter = disabledVariants.entrySet().iterator(); iter.hasNext();) {
      Map.Entry entry = (Map.Entry) iter.next();
      String variantName = (String) entry.getKey();
      List variants = (List) entry.getValue();
      String selected = getServerManager().getTestConfig().selectedVariantFor(variantName);
      if (variants.contains(selected)) {
        logger.warn("Test " + getName() + " is disabled for " + variantName + " = " + selected);
        return true;
      }
    }
    return false;
  }

  private boolean shouldDisableForJavaVersion() {
    String currentVersion = System.getProperties().getProperty("java.version");
    for (Iterator iter = disabledJavaVersion.iterator(); iter.hasNext();) {
      String version = (String) iter.next();
      if (currentVersion.matches(version)) {
        logger.warn("Test " + getName() + " is disabled for " + version);
        return true;
      }
    }
    return false;
  }

  /**
   * Not to be overridden. This is a utility function to query session locking status. To change locking please override
   * getSessionLocking() instead
   */
  public final boolean isSessionLockingTrue() {
    Boolean sessionLocking = getSessionLocking();

    // the default for session locking is true for custom and false for express
    if (sessionLocking == null) { return !isExpressMode(); }

    return sessionLocking;
  }

  protected Boolean getSessionLocking() {
    return null;
  }

  protected Boolean getSynchronousWrite() {
    return null;
  }

  public boolean isExpressMode() {
    return isExpressMode;
  }

  protected boolean canRunExpressMode() {
    return true;
  }

}
