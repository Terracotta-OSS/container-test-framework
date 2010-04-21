/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import com.tc.test.AppServerInfo;
import com.tc.test.TestConfigObject;
import com.tc.test.server.appserver.load.LowMemWorkaround;
import com.tc.text.Banner;
import com.tc.util.TcConfigBuilder;

import java.util.Collections;
import java.util.Enumeration;

import junit.extensions.TestSetup;
import junit.framework.TestSuite;

public class ServerTestSetup extends TestSetup {
  private final Class           testClass;
  private final TcConfigBuilder tcConfigBuilder;
  private ServerManager         sm;

  public ServerTestSetup(Class<? extends AbstractDeploymentTestCase> testClass) {
    this(testClass, null);
  }

  public ServerTestSetup(Class<? extends AbstractDeploymentTestCase> testClass, TcConfigBuilder configBuilder) {
    super(new TestSuite(testClass));
    this.testClass = testClass;
    this.tcConfigBuilder = configBuilder == null ? new TcConfigBuilder() : configBuilder;
  }

  protected Class getTestClass() {
    return testClass;
  }

  protected TcConfigBuilder getTcConfigBuilder() {
    return tcConfigBuilder;
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
                                            getSynchronousWrite(), Collections.EMPTY_LIST);
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

  protected boolean isWithPersistentStore() {
    // override if you please
    return false;
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

  private AbstractDeploymentTestCase getTestCase() {
    TestSuite suite = (TestSuite) getTest();
    int count = suite.testCount();
    if (count != 1) { throw new AssertionError("Expecting 1 test case, there are " + count); }

    return ((AbstractDeploymentTestCase) suite.testAt(0));
  }

  // This method not meant to be overridden -- do it in the test case itself (not the test setup)
  public final boolean isSessionLockingTrue() {
    return getTestCase().isSessionLockingTrue();
  }

  // This method not meant to be overridden -- do it in the test case itself (not the test setup)
  private final Boolean getSessionLocking() {
    return getTestCase().getSessionLocking();
  }

  // This method not meant to be overridden -- do it in the test case itself (not the test setup)
  private final Boolean getSynchronousWrite() {
    return getTestCase().getSynchronousWrite();
  }

}
