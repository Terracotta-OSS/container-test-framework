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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

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

  private AbstractDeploymentTestCase[] getTestCases() {
    TestSuite suite = (TestSuite) getTest();
    int count = suite.testCount();

    List<AbstractDeploymentTestCase> rv = new ArrayList<AbstractDeploymentTestCase>();
    for (int i = 0; i < count; i++) {
      rv.add((AbstractDeploymentTestCase) suite.testAt(i));
    }

    return rv.toArray(new AbstractDeploymentTestCase[] {});
  }

  // This method not meant to be overridden -- do it in the test case itself (not the test setup)
  public final boolean isSessionLockingTrue() {
    return queryTestCases(Query.IS_SESSION_LOCKING_TRUE);
  }

  // This method not meant to be overridden -- do it in the test case itself (not the test setup)
  private final Boolean getSessionLocking() {
    return queryTestCases(Query.SESSION_LOCKING);
  }

  // This method not meant to be overridden -- do it in the test case itself (not the test setup)
  private final Boolean getSynchronousWrite() {
    return queryTestCases(Query.SYNCHRONOUS_WRITE);
  }

  private Boolean queryTestCases(Query query) {
    AbstractDeploymentTestCase[] testCases = getTestCases();

    final Boolean first = queryTestCase(query, testCases[0]);
    for (int i = 1; i < testCases.length; i++) {
      Boolean next = queryTestCase(query, testCases[i]);
      if ((first == null && next != null) || first != next) {
        //
        throw new AssertionError("inconsistent results: " + first + " != " + next + " at index " + i);
      }
    }

    return first;
  }

  private Boolean queryTestCase(Query query, AbstractDeploymentTestCase testCase) {
    switch (query) {
      case SYNCHRONOUS_WRITE: {
        return testCase.getSynchronousWrite();
      }
      case SESSION_LOCKING: {
        return testCase.getSessionLocking();
      }
      case IS_SESSION_LOCKING_TRUE: {
        return testCase.isSessionLockingTrue();
      }
    }

    throw new AssertionError("query: " + query);
  }

  private enum Query {
    SYNCHRONOUS_WRITE, SESSION_LOCKING, IS_SESSION_LOCKING_TRUE;
  }

}
