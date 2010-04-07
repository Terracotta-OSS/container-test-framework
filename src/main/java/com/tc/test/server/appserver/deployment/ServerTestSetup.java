/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import com.tc.test.AppServerInfo;
import com.tc.test.TestConfigObject;
import com.tc.test.server.appserver.load.LowMemWorkaround;
import com.tc.text.Banner;

import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;

import junit.extensions.TestSetup;
import junit.framework.TestSuite;

public class ServerTestSetup extends TestSetup {
  private final Class      testClass;
  private final Collection extraJvmArgs;
  private final boolean    persistentMode;
  private ServerManager    sm;

  public ServerTestSetup(Class testClass) {
    this(testClass, false, Collections.EMPTY_LIST);
  }

  public ServerTestSetup(Class testClass, boolean persistentMode, Collection extraJvmArgs) {
    super(new TestSuite(testClass));
    this.testClass = testClass;
    this.persistentMode = persistentMode;
    this.extraJvmArgs = extraJvmArgs;
  }

  @Override
  protected void setUp() throws Exception {
    if (shouldDisable()) return;
    super.setUp();
    if (TestConfigObject.getInstance().isExpressMode()) {
      Banner.infoBanner("Running appserver in express mode. DSO is disabled");
      GenericServer.setDsoEnabled(false);
    }
    getServerManager();
  }

  @Override
  protected void tearDown() throws Exception {
    if (sm != null) {
      ServerManagerUtil.stopAndRelease(sm);
    }
  }

  protected ServerManager getServerManager() {
    if (sm == null) {
      try {
        sm = ServerManagerUtil.startAndBind(testClass, isWithPersistentStore(), getSessionLocking(),
                                            getSynchronousWrite(), extraJvmArgs);
      } catch (Exception e) {
        throw new RuntimeException("Unable to create server manager", e);
      }
    }
    return sm;
  }

  public AppServerInfo appServerInfo() {
    return TestConfigObject.getInstance().appServerInfo();
  }

  public DeploymentBuilder makeDeploymentBuilder(String warFileName) {
    return getServerManager().makeDeploymentBuilder(warFileName);
  }

  public boolean isWithPersistentStore() {
    return persistentMode;
  }

  public boolean shouldDisable() {
    if (LowMemWorkaround.lessThan2Gb()) {
      Banner.warnBanner("NOT RUNNNING TEST SINCE THIS MACHINE DOES NOT HAVE AT LEAST 2GB MEMORY");
      return true;
    }

    for (Enumeration e = ((TestSuite) fTest).tests(); e.hasMoreElements();) {
      Object o = e.nextElement();
      if (o instanceof AbstractDeploymentTestCase && ((AbstractDeploymentTestCase) o).shouldDisable()) { return true; }
    }
    return false;
  }

  /**
   * Not to be overriden. It's a util function to query session locking status. To change locking please override
   * getSessionLocking() instead
   */
  public final boolean isSessionLockingTrue() {
    return Boolean.TRUE.equals(getSessionLocking());
  }

  protected Boolean getSessionLocking() {
    return null;
  }

  protected Boolean getSynchronousWrite() {
    return null;
  }
}
