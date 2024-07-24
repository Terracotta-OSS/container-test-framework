/*
 * Copyright 2003-2009 Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 */
package com.tc.test.server.appserver.deployment;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.tc.test.server.appserver.StandardAppServerParameters;
import com.tc.util.TcConfigBuilder;

import junit.framework.Test;
import junit.framework.TestSuite;

import static java.lang.Integer.parseInt;
import static java.lang.System.getProperty;

public abstract class AbstractStandaloneTwoServerDeploymentTest extends AbstractDeploymentTestCase {
  public WebApplicationServer server0;
  public WebApplicationServer server1;

  public AbstractStandaloneTwoServerDeploymentTest() {
    if (commitTimeoutTaskAdded(false, true)) {
      scheduleTimeoutTask();
    }
  }

  public void setServer0(WebApplicationServer server0) {
    this.server0 = server0;
  }

  public void setServer1(WebApplicationServer server1) {
    this.server1 = server1;
  }

  @Override
  protected boolean shouldKillAppServersEachRun() {
    return false;
  }

  private static abstract class StandaloneTwoServerTestSetupBase extends ServerTestSetup {
    private final Log              logger = LogFactory.getLog(getClass());

    private final String           context0;
    private final String           context1;

    protected WebApplicationServer server0;
    protected WebApplicationServer server1;

    protected StandaloneTwoServerTestSetupBase(Class testClass, String context0, String context1) {
      this(testClass, null, context0, context1);

    }

    public StandaloneTwoServerTestSetupBase(Class testClass, TcConfigBuilder tcConfigBuilder, String context0,
                                            String context1) {
      super(testClass, tcConfigBuilder);
      this.context0 = context0;
      this.context1 = context1;
    }

    @Override
    protected void setUp() throws Exception {
      if (shouldDisable()) return;
      super.setUp();
      try {
        getServerManager();

        long l1 = System.currentTimeMillis();
        Deployment deployment0 = makeWAR(0);
        long l2 = System.currentTimeMillis();
        logger.info("### WAR build 0 " + (l2 - l1) / 1000f + " at " + deployment0.getFileSystemPath());
        Deployment deployment1 = null;
        if (null != context1) {
          deployment1 = makeWAR(1);
          long l3 = System.currentTimeMillis();
          logger.info("### WAR build 1 " + (l3 - l2) / 1000f + " at " + deployment1.getFileSystemPath());
        }

        configureTcConfig(getTcConfigBuilder());
        server0 = createServer(deployment0, context0);
        if (null != deployment1) {
          server1 = createServer(deployment1, context1);
        } else {
          server1 = createServer(deployment0, context0);
        }

        TestSuite suite = (TestSuite) getTest();
        for (int i = 0; i < suite.testCount(); i++) {
          Test t = suite.testAt(i);
          if (t instanceof AbstractStandaloneTwoServerDeploymentTest) {
            AbstractStandaloneTwoServerDeploymentTest test = (AbstractStandaloneTwoServerDeploymentTest) t;
            test.setServer0(server0);
            test.setServer1(server1);
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

    private WebApplicationServer createServer(Deployment deployment, String context) throws Exception {
      WebApplicationServer server = getServerManager().makeWebApplicationServerNoDso();
      configureServerParamers(server.getServerParameters());
      server.addWarDeployment(deployment, context);
      server.start();

      return server;
    }

    private Deployment makeWAR(int server) throws Exception {
      String context = (server == 0) ? context0 : context1;
      DeploymentBuilder builder = makeDeploymentBuilder(context + ".war");
      builder.addDirectoryOrJARContainingClass(getTestClass());
      configureWar(server, builder);
      return builder.makeDeployment();
    }

    /**
     * Override this method to add directories or jars to the builder
     */
    protected abstract void configureWar(int server, DeploymentBuilder builder);

    protected void configureTcConfig(TcConfigBuilder clientConfig) {
      // override this method to modify tc-config.xml
    }

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

  /**
   * Use this setup for two servers both running the same app context.
   */
  public static abstract class StandaloneTwoServerTestSetup extends StandaloneTwoServerTestSetupBase {
    protected StandaloneTwoServerTestSetup(Class testClass, String context) {
      super(testClass, context, null);
    }

    @Override
    final protected void configureWar(int server, DeploymentBuilder builder) {
      configureWar(builder);
    }

    protected abstract void configureWar(DeploymentBuilder builder);
  }

  /**
   * Use this setup for two servers, each with its own context. The test class and config will still be shared by both.
   * To add additional classes to just one of the contexts, override {@link #configureWar(int, DeploymentBuilder)}.
   */
  public static abstract class StandaloneTwoContextTestSetup extends StandaloneTwoServerTestSetupBase {
    protected StandaloneTwoContextTestSetup(Class testClass, String tcConfigFile, String context0, String context1) {
      super(testClass, new TcConfigBuilder(tcConfigFile), context0, context1);
    }

  }
}
