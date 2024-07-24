/*
 * Copyright 2003-2008 Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 */
package com.tc.test.server.appserver.deployment;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.tc.test.server.appserver.StandardAppServerParameters;

import junit.framework.Test;
import junit.framework.TestSuite;

import static java.lang.Integer.parseInt;
import static java.lang.System.getProperty;

public abstract class AbstractStandaloneOneServerDeploymentTest extends AbstractDeploymentTestCase {
  public WebApplicationServer server0;

  public AbstractStandaloneOneServerDeploymentTest() {
    if (commitTimeoutTaskAdded(false, true)) {
      scheduleTimeoutTask();
    }
  }

  public void setServer0(WebApplicationServer server0) {
    this.server0 = server0;
  }

  @Override
  protected boolean shouldKillAppServersEachRun() {
    return false;
  }

  public static abstract class StandaloneOneServerTestSetup extends ServerTestSetup {
    private final Log              logger = LogFactory.getLog(getClass());
    private final String           context;

    private boolean                start  = true;
    protected WebApplicationServer server0;

    protected StandaloneOneServerTestSetup(Class testClass, String context) {
      super(testClass);
      this.context = context;
    }

    protected void setStart(boolean start) {
      this.start = start;
    }

    @Override
    protected void setUp() throws Exception {
      if (shouldDisable()) return;
      super.setUp();
      try {
        getServerManager();

        long l1 = System.currentTimeMillis();
        Deployment deployment = makeWAR();
        long l2 = System.currentTimeMillis();
        logger.info("### WAR build " + (l2 - l1) / 1000f + " at " + deployment.getFileSystemPath());

        // configureTcConfig(tcConfigBuilder);
        server0 = createServer(deployment);

        TestSuite suite = (TestSuite) getTest();
        for (int i = 0; i < suite.testCount(); i++) {
          Test t = suite.testAt(i);
          if (t instanceof AbstractStandaloneOneServerDeploymentTest) {
            AbstractStandaloneOneServerDeploymentTest test = (AbstractStandaloneOneServerDeploymentTest) t;
            test.setServer0(server0);
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
        ServerManager sm = getServerManager();
        if (sm != null) {
          sm.stop();
        }
        throw e;
      }
    }

    private WebApplicationServer createServer(Deployment deployment) throws Exception {
      WebApplicationServer server = getServerManager().makeWebApplicationServerNoDso();
      server.addWarDeployment(deployment, context);
      configureServerParamers(server.getServerParameters());
      if (start) {
        server.start();
      }
      return server;
    }

    private Deployment makeWAR() throws Exception {
      DeploymentBuilder builder = makeDeploymentBuilder(context + ".war");
      builder.addDirectoryOrJARContainingClass(getTestClass());
      configureWar(builder);
      return builder.makeDeployment();
    }

    protected abstract void configureWar(DeploymentBuilder builder);

    protected void configureServerParamers(StandardAppServerParameters params) {
      if (parseInt(getProperty("java.specification.version").split("\\.")[0]) >= 16) {
        params.appendJvmArgs("--add-opens java.base/java.lang=ALL-UNNAMED" +
          " --add-opens java.base/java.io=ALL-UNNAMED" +
          " --add-opens java.base/java.util=ALL-UNNAMED" +
          " --add-opens java.base/java.security=ALL-UNNAMED" +
          " --add-opens java.base/java.net=ALL-UNNAMED");
      }
    }
  }

}
